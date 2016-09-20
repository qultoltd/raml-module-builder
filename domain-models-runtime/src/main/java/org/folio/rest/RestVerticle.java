package org.folio.rest;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerFileUpload;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.example.util.Runner;
import io.vertx.ext.dropwizard.MetricsService;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.core.eventbus.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.mail.MessagingException;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.StringUtils;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.folio.rulez.Rules;

import com.google.common.base.Joiner;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Table;
import com.google.common.io.ByteStreams;
import com.google.common.reflect.ClassPath;

import org.folio.rest.jaxrs.resource.AdminResource.PersistMethod;
import org.folio.rest.persist.MongoCRUD;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.LogUtil;
import org.folio.rest.tools.utils.OutStream;
import org.folio.rest.tools.AnnotationGrabber;
import org.folio.rest.tools.messages.MessageConsts;
import org.folio.rest.tools.messages.Messages;

public class RestVerticle extends AbstractVerticle {

  public static final String        DEFAULT_UPLOAD_BUS_ADDRS        = "admin.uploaded.files";
  private static final String       UPLOAD_PATH_TO_HANDLE           = "/apis/admin/upload";
  private static final String       DEFAULT_TEMP_DIR                = System.getProperty("java.io.tmpdir");

  public static final String        JSON_URL_MAPPINGS               = "API_PATH_MAPPINGS";

  private static final String       CORS_ALLOW_HEADER               = "Access-Control-Allow-Origin";
  private static final String       CORS_ALLOW_ORIGIN               = "Access-Control-Allow-Headers";

  private static final String       CORS_ALLOW_HEADER_VALUE         = "*";
  private static final String       CORS_ALLOW_ORIGIN_VALUE         = "Origin, Authorization, X-Requested-With, Content-Type, Accept";

  private static final String       PACKAGE_OF_IMPLEMENTATIONS      = "org.folio.rest.impl";
  private static final String       PACKAGE_OF_HOOK_INTERFACES      = "org.folio.rest.resource.interfaces";

  private static final String       SUPPORTED_CONTENT_TYPE_FORMDATA = "multipart/form-data";
  private static final String       SUPPORTED_CONTENT_TYPE_STREAMIN = "application/octet-stream";
  private static final String       SUPPORTED_CONTENT_TYPE_JSON_DEF = "application/json";
  private static final String       SUPPORTED_CONTENT_TYPE_TEXT_DEF = "text/plain";
  private static final String       SUPPORTED_CONTENT_TYPE_XML_DEF  = "application/xml";

  private static final String       FILE_UPLOAD_PARAM               = "javax.mail.internet.MimeMultipart";

  private static ValidatorFactory   validationFactory;
  private static KieSession         droolsSession;
  private final Messages            messages                        = Messages.getInstance();
  private HttpServer                server;
  private static final ObjectMapper mapper                          = new ObjectMapper();

  private int                       port                            = -1;

  private static String             className                       = RestVerticle.class.getName();

  private static final Logger       log                             = LoggerFactory.getLogger(className);

  //we look for the class and function in the class that is mapped to a requested url 
  //since we try to load via reflection an implementation of the class at runtime - better to load once and cache 
  //for subsequent calls
  private static Table<String, String, Class<?>> clazzCache       = HashBasedTable.create();
  
  private EventBus eventBus;
  
  // this is only to run via IDE - otherwise see pom which runs the verticle and
  // requires passing -cluster and preferable -cluster-home args
  public static void main(String[] args) {
    Runner.runExample(RestVerticle.class);
  }

  static {
    //validationFactory used to validate the pojos which are created from the json
    //passed in the request body in put and post requests. The constraints validated by this factory 
    //are the ones in the json schemas accompanying the raml files
    validationFactory = Validation.buildDefaultValidatorFactory();

  }

  // https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html
  // first match - no q val check
  static String acceptCheck(List<String> l, String h) {
    String hl[] = h.split(",");
    String hBest = null;
    for (int i = 0; i < hl.length; i++) {
      String mediaRange = hl[i].split(";")[0].trim();
      Iterator<String> iterator = l.iterator();
      while (iterator.hasNext()) {
        String c = iterator.next();
        if (mediaRange.compareTo("*/*") == 0 || c.equalsIgnoreCase(mediaRange)) {
          hBest = c;
          break;
        }
      }
    }
    return hBest;
  }

  @Override
  public void start(Future<Void> startFuture) throws Exception {

    //process cmd line arguments
    cmdProcessing();

    LogUtil.formatLogMessage(className, "start", "metrics enabled: " + vertx.isMetricsEnabled());

    MetricsService metricsService = MetricsService.create(vertx);

    // maps paths found in raml to the generated functions to route to when the paths are requested
    MappedClasses mappedURLs = populateConfig();

    // set of exposed urls as declared in the raml
    Set<String> urlPaths = mappedURLs.getAvailURLs();

    // create a map of regular expression to url path
    Map<String, Pattern> regex2Pattern = mappedURLs.buildURLRegex();

    // Create a router object.
    Router router = Router.router(vertx);

    
    eventBus = vertx.eventBus();
    
    // needed so that we get the body content of the request - note that this
    // will read the entire body into memory 
    final BodyHandler handler = BodyHandler.create();

    // IMPORTANT!!!
    // the body of the request will be read into memory for ALL PUT requests
    // and for POST requests with the content-types below ONLY!!!
    // multipart, for example will not be read by the body handler as vertx saves
    // multiparts and www-encoded to disk - hence multiparts will be handled differently
    // see uploadHandler further down
    router.put().handler(handler);
    router.post().consumes(SUPPORTED_CONTENT_TYPE_JSON_DEF).handler(handler);
    router.post().consumes(SUPPORTED_CONTENT_TYPE_TEXT_DEF).handler(handler);
    router.post().consumes(SUPPORTED_CONTENT_TYPE_XML_DEF).handler(handler);
    
    // run pluggable startup code in a class implementing the InitAPI interface
    // in the "org.folio.rest.impl" package
    runHook(vv -> {
      if (((Future<?>) vv).failed()) {        
        String reason = ((Future<?>) vv).cause().getMessage();        
        log.error( messages.getMessage("en", MessageConsts.InitializeVerticleFail, reason));
        startFuture.fail(reason);
        vertx.close();
        System.exit(-1);
      } else {
        log.info("init succeeded.......");
        
        try {
          // startup periodic impl if exists
          runPeriodicHook();
        } catch (Exception e2) {
          log.error(e2.getMessage(), e2);
        }

        //single handler for all url calls
        router.route("/apis/*").handler(rc -> {
          long start = System.nanoTime();
          try {
            //list of regex urls created from urls declared in the raml
            Iterator<String> iter = urlPaths.iterator();
            boolean validPath = false;
            boolean[] validRequest = { true };
            // loop over regex patterns and try to match them against the requested
            // URL if no match is found, then the requested url is not supported by
            // the ramls and we return an error - this has positive security implications as well
          while (iter.hasNext()) {
            String regexURL = iter.next();
            //try to match the requested url to the each regex pattern created from the urls in the raml
            Matcher m = regex2Pattern.get(regexURL).matcher(rc.request().path());
            if (m.find()) {
              validPath = true;
              // get the function that should be invoked for the requested
              // path + requested http_method pair
              JsonObject ret = mappedURLs.getMethodbyPath(regexURL, rc.request().method().toString());
              // if a valid path was requested but no function was found
              if (ret == null) {

                // if the path is valid and the http method is options
                // assume a cors request
                if (rc.request().method() == HttpMethod.OPTIONS) {
                  // assume cors and return header of preflight
                  // Access-Control-Allow-Origin

                  // REMOVE CORS SUPPORT FOR
                  // NOW!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

                  // rc.response().putHeader(CORS_ALLOW_ORIGIN,
                  // CORS_ALLOW_ORIGIN_VALUE);
                  // rc.response().putHeader(CORS_ALLOW_HEADER,
                  // CORS_ALLOW_HEADER_VALUE);

                  // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

                  rc.response().end();

                  return;
                }

                // the url exists but the http method requested does not match a function
                // meaning url+http method != a function
                endRequestWithError(rc, 400, true, messages.getMessage("en", MessageConsts.HTTPMethodNotSupported), 
                  validRequest);
              }
              Class<?> aClass;
              try {
                if (validRequest[0]) {
                  int groups = m.groupCount();
                  //pathParams are the place holders in the raml query string
                  //for example /admin/{admin_id}/yyy/{yyy_id} - the content in between the {} are path params
                  //they are replaced with actual values and are passed to the function which the url is mapped to
                  String[] pathParams = new String[groups];
                  for (int i = 0; i < groups; i++) {
                    pathParams[i] = m.group(i + 1);
                  }
                  //get interface mapped to this url
                  String iClazz = ret.getString(AnnotationGrabber.CLASS_NAME);
                  // convert from interface to an actual class implementing it, which appears in the impl package
                  aClass = convert2Impl(PACKAGE_OF_IMPLEMENTATIONS, iClazz);
                  Object o = null;
                  // call back the constructor of the class - gives a hook into the class not based on the apis
                  // passing the vertx and context objects in to it.
                  try {
                    o = aClass.getConstructor(Vertx.class, Context.class).newInstance(vertx, vertx.getOrCreateContext());
                  } catch (Exception e) {
                    // if no such constructor was implemented call the
                    // default no param constructor to create the object to be used to call functions on
                    o = aClass.newInstance();
                  }
                  final Object instance = o;
                  // function to invoke for the requested url
                  String function = ret.getString(AnnotationGrabber.FUNCTION_NAME);
                  // parameters for the function to invoke
                  JsonObject params = ret.getJsonObject(AnnotationGrabber.METHOD_PARAMS);
                  // all methods in the class whose function is mapped to the called url
                  // needed so that we can get a reference to the Method object and call it via reflection
                  Method[] methods = aClass.getMethods();
                  // what the api will return as output (Accept)
                  JsonArray produces = ret.getJsonArray(AnnotationGrabber.PRODUCES);
                  // what the api expects to get (content-type)
                  JsonArray consumes = ret.getJsonArray(AnnotationGrabber.CONSUMES);

                  HttpServerRequest request = rc.request();

                  //whether framework should handle the request here or pass it on to an implementing function
                  boolean handleInternally = handleInterally(request);

                  //check that the accept and content-types passed in the header of the request
                  //are as described in the raml
                  checkAcceptContentType(produces, consumes, rc, validRequest);


                  // create the array and then populate it by parsing the url parameters which are needed to invoke the function mapped 
                  //to the requested URL - array will be populated by parseParams() function
                  Iterator<Map.Entry<String, Object>> paramList = params.iterator();
                  Object[] paramArray = new Object[params.size()];                                      
                  parseParams(rc, paramList, validRequest, consumes, paramArray, pathParams);

                  if (validRequest[0]) {                   
                    
                    // check if we are dealing with a file upload , currently only multipart/form-data content-type support
                    final boolean[] isFileUpload = new boolean[] { false };
                    final int[] uploadParamPosition = new int[] { -1 };
                    params.forEach(param -> {
                      if (((JsonObject) param.getValue()).getString("type").equals(FILE_UPLOAD_PARAM)) {
                        isFileUpload[0] = true;
                        uploadParamPosition[0] = ((JsonObject) param.getValue()).getInteger("order");
                      }
                    });

                    /**
                     * handle uploads requested from the admin interface by streaming them to the disk
                     * and do not pass to an implementing function
                     */
                    if (isFileUpload[0] && handleInternally) {
                      internalUploadService(rc, validRequest);
                    }
                    
                    /**
                     * file upload requested (multipart/form-data) but the url is not to the /apis/admin/upload
                     * meaning, an implementing module is using its own upload handling, so read the content and 
                     * pass to implementing function just like any other call
                     */
                    if (isFileUpload[0] && !handleInternally) {
                      //if file upload - set needed handlers
                      // looks something like -> multipart/form-data; boundary=----WebKitFormBoundaryzeZR8KqAYJyI2jPL
                      if (consumes != null && consumes.contains(SUPPORTED_CONTENT_TYPE_FORMDATA)) {
                        request.setExpectMultipart(true);       
                        MimeMultipart mmp = new MimeMultipart();
                        //place the mmp as an argument to the 'to be called' function - at the correct position
                        paramArray[uploadParamPosition[0]] = mmp;
                        
                        request.uploadHandler(new Handler<io.vertx.core.http.HttpServerFileUpload>() {
                          
                          Buffer content = Buffer.buffer();

                          @Override
                          public void handle(HttpServerFileUpload upload) {
                            
                            // called as data comes in
                            upload.handler(new Handler<Buffer>() {
                              @Override
                              public void handle(Buffer buff) {
                                if(content == null){
                                  content = Buffer.buffer();
                                }
                                content.appendBuffer(buff);
                              }
                            });
                            upload.exceptionHandler(new Handler<Throwable>() {
                              @Override
                              public void handle(Throwable event) {
                                endRequestWithError(rc, 400, true, "unable to upload file " + event.getMessage(), validRequest);
                              }
                            });
                            // endHandler called when all data completed streaming to server
                            //called for each part in the multipart - so if uploading 2 files - will be called twice
                            upload.endHandler(new Handler<Void>() {
                              @Override
                              public void handle(Void event) {
                                
                                InternetHeaders headers = new InternetHeaders();
                                MimeBodyPart mbp = null;
                                try {
                                  mbp = new MimeBodyPart(headers, content.getBytes());
                                  mbp.setFileName(upload.filename());
                                  mmp.addBodyPart(mbp);                                  
                                  content = null;
                                } catch (MessagingException e) {
                                  // TODO Auto-generated catch block
                                  e.printStackTrace();
                                }
                              }
                            });
                          }
                        });
                      } else {
                        endRequestWithError(rc, 400, true, messages.getMessage("en", 
                          MessageConsts.ContentTypeError, SUPPORTED_CONTENT_TYPE_FORMDATA, consumes) , validRequest);
                      }
                    } 
                    else{
                      if (validRequest[0] && !handleInternally) {
                        //if request is valid - invoke it
                        for (int i = 0; i < methods.length; i++) {
                          if (methods[i].getName().equals(function)) {
                            try {
                              invoke(methods[i], paramArray, instance, rc, v -> {
                                LogUtil.formatLogMessage(className, "start", " invoking " + function);
                                sendResponse(rc, v, start);
                              });
                            } catch (Exception e1) {
                              log.error(e1.getMessage(), e1);
                              rc.response().end();
                            }
                          }
                        }
                      }
                    }
                                        
                    // register handler in case of file uploads - when the body handler is used then the entire body is read
                    // and calling the endhandler will throw an exception since there is nothing to read. so this can only be called
                    //when no body handler is associated with the path - in our case multipart/form-data
                    //real need for this is in case of bad file upload requests which dont trigger the upload end handler - so this catches is
                    if (isFileUpload[0] && !handleInternally) {
                      request.endHandler( a -> {
                        if (validRequest[0]) {
                          //if request is valid - invoke it
                          for (int i = 0; i < methods.length; i++) {
                            if (methods[i].getName().equals(function)) {
                              try {
                                invoke(methods[i], paramArray, instance, rc, v -> {
                                  LogUtil.formatLogMessage(className, "start", " invoking " + function);
                                  sendResponse(rc, v, start);
                                });
                              } catch (Exception e1) {
                                log.error(e1.getMessage(), e1);
                                rc.response().end();
                              }
                            }
                          }
                        } 
                      });
                    }
                  }
                }
              } catch (Exception e) {
                log.error(e.getMessage(), e);
                e.printStackTrace();
                endRequestWithError(rc, 400, true, messages.getMessage("en", MessageConsts.UnableToProcessRequest) + e.getMessage(),
                  validRequest);
              }
            }
          }
          if (!validPath) {
            // invalid path
            endRequestWithError(rc, 400, true, 
              messages.getMessage("en", MessageConsts.InvalidURLPath, rc.request().path()), validRequest);
          }
        } finally {}
      } );
        // routes requests on “/assets/*” to resources stored in the “assets”
        // directory.
        router.route("/assets/*").handler(StaticHandler.create("assets"));

        // In the following example all requests to paths starting with
        // /apidocs/ will get served from the directory resources/apidocs:
        // example:
        // http://localhost:8181/apidocs/index.html?raml=raml/_patrons.raml
        router.route("/apidocs/*").handler(StaticHandler.create("apidocs"));
        // startup http server on port 8181 to serve documentation

        // CHANGED FOR NOW _ REMOVE CORS
        // SUPPORT!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

        /*
         * Set<HttpMethod> corsAllowedMethods = new HashSet<HttpMethod>(); corsAllowedMethods.add(HttpMethod.GET);
         * corsAllowedMethods.add(HttpMethod.OPTIONS); corsAllowedMethods.add(HttpMethod.PUT); corsAllowedMethods.add(HttpMethod.POST);
         * corsAllowedMethods.add(HttpMethod.DELETE);
         * 
         * router.route().handler( CorsHandler.create("*").allowedMethods(corsAllowedMethods
         * ).allowedHeader("Authorization").allowedHeader("Content-Type") .allowedHeader("Access-Control-Request-Method").allowedHeader(
         * "Access-Control-Allow-Credentials") .allowedHeader("Access-Control-Allow-Origin"
         * ).allowedHeader("Access-Control-Allow-Headers"));
         */

        // !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        if (port == -1) {
          port = config().getInteger("http.port", 8081);
        }
        Integer p = port;
        server = vertx.createHttpServer().requestHandler(router::accept)
        // router object (declared in the beginning of the atrt function accepts request and will pass to next handler for
        // specified path

        .listen(
          // Retrieve the port from the configuration file - file needs to
          // be passed as arg to command line,
          // for example: -conf src/main/conf/my-application-conf.json
          // default to 8181.
          p,
          result -> {
            if (result.failed()) {
              startFuture.fail(result.cause());
            } else {
              LogUtil.formatLogMessage(className, "start", "http server for apis and docs started on port " + p + ".");
              LogUtil.formatLogMessage(className, "start", "Documentation available at: " + "http://localhost:" + Integer.toString(p)
                  + "/apidocs/");
              startFuture.complete();
            }
          });
      }
    });
  }

  /**
   * Send the result as response.
   *
   * @param rc
   *          - where to send the result
   * @param v
   *          - the result to send
   * @param start
   *          - request's start time, using JVM's high-resolution time source, in nanoseconds
   */
  private void sendResponse(RoutingContext rc, AsyncResult<Response> v, long start) {
    Response result = ((Response) ((AsyncResult<?>) v).result());
    if (result == null) {
      // catch all
      endRequestWithError(rc, 500, true, "Server error", new boolean[] { true });
      return;
    }

    try {
      HttpServerResponse response = rc.response();
      int statusCode = result.getStatus();
      // 204 means no content returned in the response, so passing
      // a chunked Transfer header is not allowed
      if (statusCode != 204) {
        response.setChunked(true);
      }
      response.setStatusCode(statusCode);

      // !!!!!!!!!!!!!!!!!!!!!! CORS commented OUT!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
      // response.putHeader("Access-Control-Allow-Origin", "*");

      for (Entry<String, List<String>> entry : result.getStringHeaders().entrySet()) {
        String jointValue = Joiner.on("; ").join(entry.getValue());
        response.headers().add(entry.getKey(), jointValue);
      }

      Object entity = result.getEntity();
      if (entity instanceof OutStream) {
        entity = ((OutStream) entity).getData();
      }
      if (entity != null) {
        response.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entity));
      }
    } catch (Exception e) {
      log.error(e.getMessage(), e);
      e.printStackTrace();
    } finally {
      rc.response().end();
    }

    long end = System.nanoTime();
    
    StringBuffer sb = new StringBuffer();
    if (log.isDebugEnabled()) {
      sb.append(rc.getBodyAsString());
    } 
    LogUtil.formatStatsLogMessage(rc.request().remoteAddress().toString(), rc.request().method().toString(),
      rc.request().version().toString(), rc.response().getStatusCode(), (((end - start) / 1000000)), rc.response().bytesWritten(),
      rc.request().path(), rc.request().query(), rc.response().getStatusMessage(), sb.toString());
  }

  private void endRequestWithError(RoutingContext rc, int status, boolean chunked, String message,  boolean[] isValid) {
    if (isValid[0]) {
      log.error(rc.request().absoluteURI() + " [ERROR] " + message);
      rc.response().setChunked(chunked);
      rc.response().setStatusCode(status);
      rc.response().write(message);
      rc.response().end();
    }
    // once we are here the call is not valid
    isValid[0] = false;
  }

  public void invoke(Method method, Object[] params, Object o, RoutingContext rc, Handler<AsyncResult<Response>> resultHandler) {
    Context context = vertx.getOrCreateContext();
    Object[] newArray = new Object[params.length];
    for (int i = 0; i < params.length - 2; i++) {
      newArray[i] = params[i];
    }
    newArray[params.length - 2] = resultHandler;
    newArray[params.length - 1] = getVertx().getOrCreateContext();
    // newArray[params.length+1] = response;
    // params =
    context.runOnContext(v -> {
      try {
        method.invoke(o, newArray);
        // response.setChunked(true);
        // response.setStatusCode(((Response)result).getStatus());
      } catch (Exception e) {
        log.error(e.getMessage(), e);
        e.printStackTrace();
        String message;
        try {
          // catch exception for now in case of null point and show generic
          // message
          message = e.getCause().getMessage();
        } catch (Throwable ee) {
          message = messages.getMessage("en", MessageConsts.UnableToProcessRequest);
        }
        endRequestWithError(rc, 400, true, message, new boolean[] { true });
      }

    });
  }

  public JsonObject loadConfig(String configFile) {
    try {
      byte[] jsonData = ByteStreams.toByteArray(getClass().getClassLoader().getResourceAsStream(configFile));
      return new JsonObject(new String(jsonData));
    } catch (IOException e) {
      log.error(e.getMessage(), e);
      e.printStackTrace();
    }
    return new JsonObject();
  }

  private static String replaceLast(String string, String substring, String replacement) {
    int index = string.lastIndexOf(substring);
    if (index == -1)
      return string;
    return string.substring(0, index) + replacement + string.substring(index + substring.length());
  }

  /**
   * Return the implementing class.
   *
   * @param implDir
   *          - package name where to search
   * @param interface2check
   *          - class name of the required interface
   * @return implementing class
   * @throws IOException
   *           - if the attempt to read class path resources (jar files or directories) failed.
   * @throws ClassNotFoundException
   *           - if no class in implDir implements the interface
   */
  private static Class<?> convert2Impl(String implDir, String interface2check) throws IOException, ClassNotFoundException {
    Class<?> cachedClazz = clazzCache.get(implDir, interface2check);
    if(cachedClazz != null){
      log.debug("returned class from cache " + cachedClazz.getName());
      return cachedClazz;
    }
    ClassPath classPath = ClassPath.from(Thread.currentThread().getContextClassLoader());
    ImmutableSet<ClassPath.ClassInfo> classes = classPath.getTopLevelClasses(implDir);
    Class<?> impl = null;
    for (ClassPath.ClassInfo info : classes) {
      try {
        Class<?> clazz = Class.forName(info.getName());
        for (Class<?> anInterface : clazz.getInterfaces()) {
          if (!anInterface.getName().equals(interface2check)) {
            continue;
          }
          if (impl != null) {
            throw new RuntimeException("Duplicate implementation of " + interface2check + " in " + implDir + ": " + impl.getName() + ", "
                + clazz.getName());
          }
          impl = clazz;
        }
      } catch (ClassNotFoundException e) {
        log.error(e.getMessage(), e);
        e.printStackTrace();
      }
    }
    if (impl == null) {
      throw new ClassNotFoundException("Implementation of " + interface2check + " not found in " + implDir);
    }
    clazzCache.put(implDir, interface2check, impl);
    return impl;
  }

  private MappedClasses populateConfig() {
    MappedClasses mappedURLs = new MappedClasses();
    JsonObject jObjClasses = new JsonObject();
    try {
      jObjClasses.mergeIn(AnnotationGrabber.generateMappings());
    } catch (Exception e) {
      log.error(e.getMessage(), e);
      e.printStackTrace();
    }
    // loadConfig(JSON_URL_MAPPINGS);
    Set<String> classURLs = jObjClasses.fieldNames();
    classURLs.forEach(classURL -> {
      System.out.println(classURL);
      JsonObject jObjMethods = jObjClasses.getJsonObject(classURL);
      Set<String> methodURLs = jObjMethods.fieldNames();
      jObjMethods.fieldNames();
      methodURLs.forEach(methodURL -> {
        Object val = jObjMethods.getValue(methodURL);
        if (val instanceof JsonArray) {
          ((JsonArray) val).forEach(entry -> {
            String pathRegex = ((JsonObject) entry).getString("regex2method");
            ((JsonObject) entry).put(AnnotationGrabber.CLASS_NAME, jObjMethods.getString(AnnotationGrabber.CLASS_NAME));
            ((JsonObject) entry).put(AnnotationGrabber.INTERFACE_NAME, jObjMethods.getString(AnnotationGrabber.INTERFACE_NAME));
            mappedURLs.addPath(pathRegex, (JsonObject) entry);
          });
        }
      });
    });
    return mappedURLs;
  }

  @Override
  public void stop(Future<Void> stopFuture) throws Exception {
    // TODO Auto-generated method stub
    super.stop();
    MongoCRUD.stopEmbeddedMongo();
    PostgresClient.stopEmbeddedPostgres();
    try {
      droolsSession.dispose();
    } catch (Exception e) {}
    // removes the .lck file associated with the log file
    LogUtil.closeLogger();
    runShutdownHook(v -> {
      if (v.succeeded()) {
        stopFuture.complete();
      } else {
        stopFuture.fail("shutdown hook failed....");
      }
    });
  }

  /*
   * implementors of the InitAPI interface must call back the handler in there init() implementation like this:
   * resultHandler.handle(io.vertx.core.Future.succeededFuture(true)); or this will hang
   */
  private void runHook(Handler<AsyncResult<Boolean>> resultHandler) throws Exception {
    try {
      Class<?> aClass = convert2Impl(PACKAGE_OF_IMPLEMENTATIONS, PACKAGE_OF_HOOK_INTERFACES + ".InitAPI");
      Class<?>[] paramArray = new Class[] { Vertx.class, Context.class, Handler.class };
      Method method = aClass.getMethod("init", paramArray);
      method.invoke(aClass.newInstance(), vertx, vertx.getOrCreateContext(), resultHandler);
      LogUtil.formatLogMessage(getClass().getName(), "runHook",
        "One time hook called with implemented class " + "named " + aClass.getName());
    } catch (ClassNotFoundException e) {
      // no hook implemented, this is fine, just startup normally then
      resultHandler.handle(io.vertx.core.Future.succeededFuture(true));
    }
  }

  private void runPeriodicHook() throws Exception {
    try {
      Class<?> aClass = convert2Impl(PACKAGE_OF_IMPLEMENTATIONS, PACKAGE_OF_HOOK_INTERFACES + ".PeriodicAPI");
      Class<?>[] paramArray = new Class[] {};
      Method method = aClass.getMethod("runEvery", paramArray);
      Object delay = method.invoke(aClass.newInstance());
      LogUtil.formatLogMessage(getClass().getName(), "runPeriodicHook",
        "Periodic hook called with implemented class " + "named " + aClass.getName());
      vertx.setPeriodic(((Long) delay).longValue(), new Handler<Long>() {
        @Override
        public void handle(Long aLong) {
          try {
            Class<?>[] paramArray1 = new Class[] { Vertx.class, Context.class };
            Method method1 = aClass.getMethod("run", paramArray1);
            method1.invoke(aClass.newInstance(), vertx, vertx.getOrCreateContext());
          } catch (Exception e) {
            log.error(e.getMessage(), e);
            e.printStackTrace();
          }
        }
      });
    } catch (ClassNotFoundException e) {
      // no hook implemented, this is fine, just startup normally then
      LogUtil.formatLogMessage(getClass().getName(), "runPeriodicHook", "no periodic implementation found, continuing with deployment");
    }
  }

  private void runShutdownHook(Handler<AsyncResult<Void>> resultHandler) throws Exception {
    try {
      Class<?> aClass = convert2Impl(PACKAGE_OF_IMPLEMENTATIONS, PACKAGE_OF_HOOK_INTERFACES + ".ShutdownAPI");
      Class<?>[] paramArray = new Class[] { Vertx.class, Context.class, Handler.class };
      Method method = aClass.getMethod("shutdown", paramArray);
      method.invoke(aClass.newInstance(), vertx, vertx.getOrCreateContext(), resultHandler);
      LogUtil.formatLogMessage(getClass().getName(), "runShutdownHook",
        "shutdown hook called with implemented class " + "named " + aClass.getName());
    } catch (ClassNotFoundException e) {
      // no hook implemented, this is fine, just startup normally then
      LogUtil.formatLogMessage(getClass().getName(), "runShutdownHook", "no shutdown hook implementation found, continuing with shutdown");
      resultHandler.handle(io.vertx.core.Future.succeededFuture());
    }
  }

  private void cmdProcessing() throws Exception {
    String importDataPath = null;
    String droolsPath = null;
    // TODO need to add a normal command line parser
    List<String> cmdParams = processArgs();

    if (cmdParams != null) {
      for (Iterator iterator = cmdParams.iterator(); iterator.hasNext();) {
        String param = (String) iterator.next();
        if (param.startsWith("embed_mongo=true")) {
          MongoCRUD.setIsEmbedded(true);
        } else if (param.startsWith("-Dhttp.port=")) {
          port = Integer.parseInt(param.split("=")[1]);
          LogUtil.formatLogMessage(className, "cmdProcessing", "port to listen on " + port);
        } else if (param.startsWith("drools_dir=")) {
          droolsPath = param.split("=")[1];
          LogUtil.formatLogMessage(className, "cmdProcessing", "Drools rules file dir set to " + droolsPath);
        } else if (param.startsWith("db_connection=")) {
          String dbconnection = param.split("=")[1];
          PostgresClient.setConfigFilePath(dbconnection);
          PostgresClient.setIsEmbedded(false);
          LogUtil.formatLogMessage(className, "cmdProcessing", "Setting path to db config file....  " + dbconnection);
        } else if (param.startsWith("embed_postgres=true")) {
          // allow setting config() from unit test mode which runs embedded

          LogUtil.formatLogMessage(className, "cmdProcessing", "Using embedded postgres... starting... ");

          // this blocks
          PostgresClient.setIsEmbedded(true);
          PostgresClient.setConfigFilePath(null);
        } else if (param.startsWith("mongo_connection=")) {
          String dbconnection = param.split("=")[1];
          MongoCRUD.setConfigFilePath(dbconnection);
          MongoCRUD.setIsEmbedded(false);
          LogUtil.formatLogMessage(className, "cmdProcessing", "Setting path to mongo config file....  " + dbconnection);

        } else if (param != null && param.startsWith("postgres_import_path=")) {
          try {
            importDataPath = param.split("=")[1];
            System.out.println("Setting path to import DB file....  " + importDataPath);
          } catch (Exception e) {
            // any problems - print exception and continue
            e.printStackTrace();
          }
        }
      }

      if (PostgresClient.isEmbedded() || importDataPath != null) {
        PostgresClient.getInstance(vertx).startEmbeddedPostgres();
      }

      if (MongoCRUD.isEmbedded()) {
        MongoCRUD.getInstance(vertx).startEmbeddedMongo();
      }

      if (importDataPath != null) {
        // blocks as well for now
        System.out.println("Import DB file....  " + importDataPath);
        PostgresClient.getInstance(vertx).importFileEmbedded(importDataPath);
      }
    }

    try {
      droolsSession = new Rules(droolsPath).buildSession();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * look for the boundary and return just the multipart/form-data multipart/form-data boundary=----WebKitFormBoundaryP8wZiNAoFszXOXEt if
   * boundary doesnt exist that return original string
   */
  private String removeBoundry(String contenttype) {
    int idx = contenttype.indexOf("boundary");
    if (idx != -1) {
      return contenttype.substring(0, idx - 1);
    }
    return contenttype;
  }

  /**
   * check accept and content-type headers if no - set the request asa not valid and return error to user
   */
  private void checkAcceptContentType(JsonArray produces, JsonArray consumes, RoutingContext rc, boolean[] validRequest) {
    /*
     * NOTE that the content type and accept headers will accept a partial match - for example: if the raml indicates a text/plain and an
     * application/json content-type and only one is passed - it will accept it
     */
    // check allowed content types in the raml for this resource + method
    HttpServerRequest request = rc.request();
    if (consumes != null && validRequest[0]) {
      // get the content type passed in the request
      // if this was left out by the client they must add for request to return
      // clean up simple stuff from the clients header - trim the string and remove ';' in case
      // it was put there as a suffix
      String contentType = StringUtils.defaultString(request.getHeader("Content-type")).replace(";", "").trim();
      if (!consumes.contains(removeBoundry(contentType))) {
        endRequestWithError(rc, 400, true, messages.getMessage("en", MessageConsts.ContentTypeError, consumes, contentType),
          validRequest);
      }
    }

    // type of data expected to be returned by the server
    if (produces != null && validRequest[0]) {
      String accept = StringUtils.defaultString(request.getHeader("Accept"));
      if (acceptCheck(produces.getList(), accept) == null) {
        // use contains because multiple values may be passed here
        // for example json/application; text/plain mismatch of content type found
        endRequestWithError(rc, 400, true, messages.getMessage("en", MessageConsts.AcceptHeaderError, produces, accept),
          validRequest);
      }
    }
  }

  private void parseParams(RoutingContext rc, Iterator<Map.Entry<String, Object>> paramList, boolean[] validRequest, JsonArray consumes,
      Object[] paramArray, String[] pathParams) {

    HttpServerRequest request = rc.request();
    MultiMap queryParams = request.params();
    int pathParamsIndex[] = new int[] { pathParams.length };

    paramList.forEachRemaining(entry -> {
      if (validRequest[0]) {
        String valueName = ((JsonObject) entry.getValue()).getString("value");
        String valueType = ((JsonObject) entry.getValue()).getString("type");
        String paramType = ((JsonObject) entry.getValue()).getString("param_type");
        int order = ((JsonObject) entry.getValue()).getInteger("order");
        Object defaultVal = ((JsonObject) entry.getValue()).getValue("default_value");

        // validation of query params (other then enums), object in body (not including drools),
        // and some header params validated by jsr311 (aspects) - the rest are handled in the code here
        // handle un-annotated parameters - this is assumed to be
        // entities in HTTP BODY for post and put requests or the 2 injected params
        // (vertx context and vertx handler) - file uploads are also not annotated but are not handled here due
        // to their async upload - so explicitly skip them
        if (AnnotationGrabber.NON_ANNOTATED_PARAM.equals(paramType) && !FILE_UPLOAD_PARAM.equals(valueType)) {
          try {
            // this will also validate the json against the pojo created from the schema
            Class<?> entityClazz = Class.forName(valueType);

            if (!valueType.equals("io.vertx.core.Handler") && !valueType.equals("io.vertx.core.Context")) {
              // we have special handling for the Result Handler and context

              paramArray[order] = mapper.readValue(rc.getBodyAsString(), entityClazz);

              Set<? extends ConstraintViolation<?>> validationErrors = validationFactory.getValidator().validate(paramArray[order]);
              if (validationErrors.size() > 0) {
                StringBuffer sb = new StringBuffer();
                for (ConstraintViolation<?> cv : validationErrors) {
                  sb.append("\n" + cv.getPropertyPath() + "  " + cv.getMessage() + ",");
                }
                endRequestWithError(rc, 400, true, "Object validation errors " + sb.toString(), validRequest);
              }

              // complex rules validation here (drools) - after simpler validation rules pass -
              try {
                // if no /rules exist then drools session will be null
                // TODO support adding .drl files dynamically to db / dir
                // and having them picked up
                if (droolsSession != null) {
                  // add object to validate to session
                  FactHandle handle = droolsSession.insert(paramArray[order]);
                  // run all rules in session on object
                  droolsSession.fireAllRules();
                  // remove the object from the session
                  droolsSession.delete(handle);
                }
              } catch (Exception e) {
                log.error(e.getMessage(), e);
                e.printStackTrace();
                endRequestWithError(rc, 400, true, e.getCause().getMessage(), validRequest);
              }
              // }
            }
          } catch (Exception e) {
            log.error(e.getMessage(), e);
            e.printStackTrace();
            endRequestWithError(rc, 400, true, "Json content error " + e.getMessage(), validRequest);

          }
        } else if (AnnotationGrabber.HEADER_PARAM.equals(paramType)) {
          // handle header params - read the header field from the
          // header (valueName) and get its value
          String value = request.getHeader(valueName);
          // set the value passed from the header as a param to the function
          paramArray[order] = value;
        } else if (AnnotationGrabber.PATH_PARAM.equals(paramType)) {
          // these are placeholder values in the path - for example
          // /patrons/{patronid} - this would be the patronid value
          paramArray[order] = pathParams[pathParamsIndex[0] - 1];
          pathParamsIndex[0] = pathParamsIndex[0] - 1;
        } else if (AnnotationGrabber.QUERY_PARAM.equals(paramType)) {
          String param = queryParams.get(valueName);
          // support enum, numbers or strings as query parameters
          try {
            if (valueType.contains("String")) {
              // regular string param in query string - just push value
              if (param == null && defaultVal != null) {
                // no value passed - check if there is a default value
                paramArray[order] = (String) defaultVal;
              } else {
                paramArray[order] = param;
              }
            } else if (valueType.contains("int")) {
              // cant pass null to an int type - replace with zero
              if (param == null) {
                if (defaultVal != null) {
                  paramArray[order] = Integer.valueOf((String) defaultVal);
                } else {
                  paramArray[order] = 0;
                }
              } else {
                paramArray[order] = Integer.valueOf(param).intValue();
              }
            } else if (valueType.contains("BigDecimal")) {
              // cant pass null to an int type - replace with zero
              if (param == null) {
                if (defaultVal != null) {
                  paramArray[order] = new BigDecimal((String) defaultVal);
                } else {
                  paramArray[order] = new BigDecimal(0);
                }
              } else {
                paramArray[order] = new BigDecimal(param.replaceAll(",", "")); // big decimal can contain ","
              }
            } else { // enum object type
              try {
                String enumClazz = replaceLast(valueType, ".", "$");
                Class<?> enumClazz1 = Class.forName(enumClazz);
                if (enumClazz1.isEnum()) {
                  Object defaultEnum = null;
                  Object[] vals = enumClazz1.getEnumConstants();
                  for (int i = 0; i < vals.length; i++) {
                    if (vals[i].toString().equals(defaultVal)) {
                      defaultEnum = vals[i];
                    }
                    // set default value (if there was one in the raml)
                    // in case no value was passed in the request
                    if (param == null && defaultEnum != null) {
                      paramArray[order] = defaultEnum;
                      break;
                    }
                    // make sure enum value is valid by converting the string to an enum
                    else if (vals[i].toString().equals(param)) {
                      paramArray[order] = vals[i];
                      break;
                    }
                    if (i == vals.length - 1) {
                      // if enum passed is not valid, replace with default value
                      paramArray[order] = defaultEnum;
                    }
                  }
                }
              } catch (Exception ee) {
                log.error(ee.getMessage(), ee);
                ee.printStackTrace();
                validRequest[0] = false;
              }
            }

          } catch (Exception e) {
            log.error(e.getMessage(), e);
            e.printStackTrace();
            validRequest[0] = false;
          }
        }
      }
    });
  }
  
  private boolean handleInterally(HttpServerRequest request){
    if(UPLOAD_PATH_TO_HANDLE.equals(request.path())){
      return true;
    }
    return false;
  }
  
  private void internalUploadService(RoutingContext rc, boolean []validRequest){
    HttpServerRequest request = rc.request();
    if(request.getParam("file_name") == null){
      //handle validation manually since not using the built in framework for validation
      endRequestWithError(rc, 400, true, messages.getMessage("en", MessageConsts.FileUploadError, ", file_name can not be null"), validRequest);
      return;
    }
    request.pause();
    String filename = DEFAULT_TEMP_DIR + "/" + System.currentTimeMillis() + "_" + request.getParam("file_name");
    vertx.fileSystem().open(filename, new io.vertx.core.file.OpenOptions(), ares -> {
      try {
        io.vertx.core.file.AsyncFile file = ares.result();
        io.vertx.core.streams.Pump pump = io.vertx.core.streams.Pump.pump(request, file);
        request.endHandler(v1 -> file.close(v2 -> {
          PersistMethod persistMethod = PersistMethod.SAVE;
          String requestedPersistMethod = request.getParam("persist_method");
          if(requestedPersistMethod != null){
            if(PersistMethod.valueOf(requestedPersistMethod) != null){
              persistMethod = PersistMethod.valueOf(requestedPersistMethod);
            }
          }
          System.out.println("Uploaded to " + filename);
          if(persistMethod == PersistMethod.SAVE_AND_NOTIFY){
            String address = request.getParam("bus_address");
            if(address == null){
              address = DEFAULT_UPLOAD_BUS_ADDRS;
            }
            DeliveryOptions dOps = new DeliveryOptions();
            dOps.setSendTimeout(5000);
            eventBus.send(address, filename, dOps, rep -> {
              if(rep.succeeded()){
                log.debug("Delivered Messaged of uploaded file " + filename);
                request.response().setStatusCode(204);
                request.response().end();
              }
              else{
                log.error("Unable to deliver message of uploaded file " + filename);
                request.response().setStatusCode(204);
                request.response().setStatusMessage("The file was saved, but unable to notify of the upload to listening services");
                request.response().end();
              }
            });
          }
          else{
            request.response().setStatusCode(204);
            request.response().end();
          }
          log.info("Successfully uploaded file " + filename);
        }));
        pump.start();
        request.resume();
      } catch (Exception e) {
        e.printStackTrace();
        endRequestWithError(rc, 400, true, messages.getMessage("en", MessageConsts.FileUploadError, e.getMessage()), validRequest);

      }
    });
  }

}