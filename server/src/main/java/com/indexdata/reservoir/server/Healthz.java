package com.indexdata.reservoir.server;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.folio.tlib.RouterCreator;
import org.folio.tlib.postgres.TenantPgPool;

public class Healthz implements RouterCreator {

  public static Future<Void> checkDb(Vertx vertx) {
    TenantPgPool pool = TenantPgPool.pool(vertx, "x"); // not using the tenant for anything
    return pool.query("SELECT 1").execute().mapEmpty();
  }

  void healthHandler(Vertx vertx, RoutingContext ctx) {
    ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
    checkDb(vertx)
        .onSuccess(x -> {
          ctx.response().end("OK");
        })
        .onFailure(err -> {
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
