package com.indexdata.reservoir.server;

import io.vertx.core.Vertx;
import io.vertx.openapi.contract.OpenAPIContract;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenApiContractTest {

  @Test
  void serverContractCanBeLoaded() throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      OpenAPIContract.from(vertx, "openapi/reservoir.yaml")
          .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
  }
}
