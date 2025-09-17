package com.indexdata.reservoir.server;

import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class NativeMain {
  /**
   * Main entry point for the Reservoir server in native mode.
   *
   * <p>
   * This method initializes the server and starts the main verticle, but does *not*
   * inspect args. Only used in native mode until we pass the Main-Verticle manifest
   * attribute to the native image.
   * </p>
   *
   * @param args command line arguments
   */

  public static void main(String[] args) {
    final Logger log = LogManager.getLogger(NativeMain.class);

    System.setProperty("org.marc4j.marc.MarcFactory", "org.marc4j.marc.impl.MarcFactoryImpl");
    log.info("Starting Reservoir server");
    Vertx.vertx()
        .deployVerticle(new MainVerticle())
        .onComplete(x -> {
          if (x.succeeded()) {
            log.info("Reservoir server started successfully.");
          } else {
            log.error("Failed to start Reservoir server.", x.cause());
            System.exit(1);
          }
        });
  }
}
