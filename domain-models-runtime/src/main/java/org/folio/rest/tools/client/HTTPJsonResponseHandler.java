package org.folio.rest.tools.client;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * @author shale
 *
 */
class HTTPJsonResponseHandler implements Handler<HttpClientResponse> {

  private static final Logger log = LoggerFactory.getLogger(HTTPJsonResponseHandler.class);

  CompletableFuture<Response> cf;
  String endpoint;
  RollBackURL rollbackURL;
  HttpClient httpClient;

  public HTTPJsonResponseHandler(String endpoint, CompletableFuture<Response> cf){
    this.cf = cf;
    this.endpoint = endpoint;
  }

  @Override
  public void handle(HttpClientResponse hcr) {
    boolean hasBody[] = new boolean[]{false};
    hcr.endHandler( eh -> {
      //needed in cases where there is no body content
      if(!hasBody[0]){
        Response r = new Response();
        r.code = hcr.statusCode();
        r.endpoint = this.endpoint;
        r.headers = hcr.headers();
        if(!Response.isSuccess(r.code)){
          r.populateError(this.endpoint, r.code, hcr.statusMessage());
        }
        cf.complete(r);
      }
      if(httpClient != null){
        //this is not null when autoclose = true
        httpClient.close();
      }
    });
    hcr.bodyHandler( bh -> {
      hasBody[0] = true;
      Response r = new Response();
      r.code = hcr.statusCode();
      r.endpoint = this.endpoint;
      r.headers = hcr.headers();
      if(Response.isSuccess(r.code)){
        handleSuccess(bh, r);
      } else{
        String message = hcr.statusMessage();
        if(bh != null){
          message = bh.toString();
        }
        r.populateError(this.endpoint, r.code, message);
        cf.complete(r);
      }

      if(HttpModuleClient2.cache != null && r.body != null) {
        try {
          HttpModuleClient2.cache.put(endpoint, cf.get());
        } catch (Exception e) {
          log.error(e.getMessage(), e);
        }
      }
/*      if(r.error != null && rollbackURL != null){

      }*/
    });
    hcr.exceptionHandler( eh -> {
      if(httpClient != null){
        //this is not null when autoclose = true
        try {
          httpClient.close();
        } catch (Exception e) {
          log.error("HTTPJsonResponseHandler class tried closing a client that was closed, this may be ok. " + e.getMessage(), e);
        }
      }
      cf.completeExceptionally(eh);
    });
  }

  private void handleSuccess(Buffer bh, Response r) {
    if(r.code == 204 || bh.length() == 0) {
      r.body = null;
      cf.complete(r);
    } else  {
      try {
        r.body = bh.toJsonObject();
        cf.complete(r);
      } catch (DecodeException decodeException) {
        cf.completeExceptionally(decodeException);
      }
    }
  }

}

