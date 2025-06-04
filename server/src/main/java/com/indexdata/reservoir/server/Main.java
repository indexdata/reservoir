package com.indexdata.reservoir.server;

import io.vertx.core.Vertx;
import io.vertx.core.logging.Log4j2LogDelegateFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {
  /**
   * Main entry point for the Reservoir server.
   *
   * @param args command line arguments
   */

  public static void main(String[] args) {
    System.out.println("Starting Reservoir server (standard output message)");
    System.setProperty("vertx.logger-delegate-factory-class-name",
        Log4j2LogDelegateFactory.class.getName());
    Logger log = LogManager.getLogger(Main.class);
    log.info("Starting Reservoir server...");

    Vertx.vertx()
        .deployVerticle(new MainVerticle())
        .onComplete(x -> {
          if (x.succeeded()) {
            log.info("Reservoir server started successfully.");
          } else {
            log.error("Failed to start Reservoir server.", x.cause());
          }
        });
  }
}
