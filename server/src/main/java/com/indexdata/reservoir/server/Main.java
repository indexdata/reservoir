package com.indexdata.reservoir.server;

import io.vertx.core.Vertx;
import io.vertx.core.logging.Log4j2LogDelegateFactory;

public class Main {
  /**
   * Main entry point for the Reservoir server.
   *
   * @param args command line arguments
   */

  public static void main(String[] args) {
    System.setProperty("vertx.logger-delegate-factory-class-name",
        Log4j2LogDelegateFactory.class.getName());
    Vertx.vertx()
        .deployVerticle(new MainVerticle());
  }
}
