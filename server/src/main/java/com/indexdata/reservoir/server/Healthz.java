package com.indexdata.reservoir.server;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.tlib.RouterCreator;
import org.folio.tlib.postgres.TenantPgPool;

public class Healthz implements RouterCreator {

  private static final Logger log = LogManager.getLogger(Healthz.class);

  private static final int DB_CONNECTION_TIMEOUT_MS = 500;
  private static final int DB_QUERY_TIMEOUT_MS = 500;

  /** Check the database connection. */
  public static Future<Void> checkDb(Vertx vertx) {
    TenantPgPool pool = TenantPgPool.pool(vertx, "x", "read"); // not using the tenant for anything
    return pool.getConnection()
      .timeout(DB_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .recover(e -> Future.failedFuture("Failed to get DB connection: " + e.getMessage()))
      .compose(conn -> conn.query("SELECT 1")
        .execute()
        .timeout(DB_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .recover(e -> Future.failedFuture("Failed to execute query: " + e.getMessage()))
        .eventually(() -> conn.close())
      )
      .mapEmpty();
  }

  void healthHandler(Vertx vertx, RoutingContext ctx) {
    ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
    checkDb(vertx)
        .onSuccess(x -> {
          ctx.response().end("OK");
        })
        .onFailure(err -> {
          log.error("Health check failed", err);
          ctx.response().setStatusCode(500).end("Internal Server Error " + err.getMessage());
        });
  }

  @Override
  public Future<Router> createRouter(Vertx vertx) {
    Router router = Router.router(vertx);
    router.route(HttpMethod.GET, "/healthz").handler(ctx -> healthHandler(vertx, ctx));
    return Future.succeededFuture(router);
  }
}
