package com.indexdata.reservoir.server;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import org.folio.okapi.common.ModuleVersionReporter;
import org.folio.tlib.RouterCreator;

public class MainPage implements RouterCreator {

  private final ModuleVersionReporter moduleVersionReporter;

  MainPage(ModuleVersionReporter m) {
    this.moduleVersionReporter = m;
  }

  @Override
  public Future<Router> createRouter(Vertx vertx) {
    Router router = Router.router(vertx);
    router.route(HttpMethod.GET, "/").handler(ctx -> {
      ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json");

      String baseUrl = ctx.request().absoluteURI().replaceAll("/$", "");
      JsonObject links = new JsonObject()
          .put("clusters", baseUrl + "/reservoir/clusters")
          .put("configMatchKeys", baseUrl + "/reservoir/config/matchkeys")
          .put("configModules", baseUrl + "/reservoir/config/modules")
          .put("configOai", baseUrl + "/reservoir/config/oai")
          .put("oai", baseUrl + "/reservoir/oai")
          .put("pmhClients", baseUrl + "/reservoir/pmh-clients")
          .put("records", baseUrl + "/reservoir/records")
          .put("sru", baseUrl + "/reservoir/sru")
          .put("upload", baseUrl + "/reservoir/upload")
          ;
      JsonObject response = new JsonObject()
          .put("links", links)
          .put("name", moduleVersionReporter.getModule())
          .put("version", moduleVersionReporter.getVersion())
          .put("revision", moduleVersionReporter.getCommitId());
      ctx.response().end(response.encode());
    });
    return Future.succeededFuture(router);
  }

}
