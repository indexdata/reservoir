package com.example;

import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.ModuleId;

public class Main {
  /**
   * Main entry point for the Example server.
   *
   * @param args command line arguments
   */

  private static final Logger logger = LogManager.getLogger(Main.class);

  /**
   * This is the main entry point for the Example server.
   */
  public static void main(String[] args) {
    ModuleId moduleId = new ModuleId("mod-1.2.3-SNAPSHOT.11");
    Vertx.vertx()
        .deployVerticle(new MainVerticle())
        .onSuccess(id -> logger.info(moduleId.toString() + " ✅ Started"))
        .onFailure(
            failure -> {
              logger.warn("🚨 Deployment failed", failure);
            });
  }

}
