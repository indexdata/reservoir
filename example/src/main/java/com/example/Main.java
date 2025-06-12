package com.example;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  /**
   * Main entry point for the Example server.
   *
   * @param args command line arguments
   */

  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  /**
   * This is the main entry point for the Example server.
   */
  public static void main(String[] args) {
    Vertx.vertx()
        .deployVerticle(new MainVerticle())
        .onSuccess(id -> logger.info("✅ Started"))
        .onFailure(
            failure -> {
              logger.warn("🚨 Deployment failed", failure);
            });
  }

}
