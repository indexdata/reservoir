package org.folio.reservoir.client;

import io.vertx.core.Vertx;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main {
  static final Logger log = LogManager.getLogger(Main.class);

  /**
   * Main program for client.
   * @param args command-line args
   */
  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    Client.exec(vertx, args)
        .eventually(x -> vertx.close())
        .onFailure(e -> {
          log.error(e.getMessage(), e);
          System.exit(1);
        });
  }
}
