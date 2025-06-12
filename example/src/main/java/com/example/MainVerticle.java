package com.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainVerticle extends AbstractVerticle {
  private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    vertx.createHttpServer()
        .requestHandler(req -> {
          req.response()
              .putHeader("content-type", "text/plain")
              .end("Hello from Vert.x!");
        })
        .listen(8888).onComplete(http -> {
          if (http.succeeded()) {
            startPromise.complete();
            logger.info("HTTP server started on port 8888");
          } else {
            startPromise.fail(http.cause());
          }
        });
  }
}
