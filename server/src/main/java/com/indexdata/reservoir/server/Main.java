package com.indexdata.reservoir.server;

import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  /**
   * Main entry point for the Reservoir server.
   *
   * @param args command line arguments
   */

  public static void main(String[] args) {
    System.out.println("Starting Reservoir server (standard output message)");

    final org.apache.logging.log4j.Logger log2 = LogManager.getLogger(Main.class);

    final Logger log = LoggerFactory.getLogger(Main.class);

    log.info("Starting Reservoir server (SLF4J message)");
    log2.info("Starting Reservoir server (Log4j2 message)");

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
