package com.indexdata.reservoir.server;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.Config;
import org.folio.okapi.common.ModuleVersionReporter;
import org.folio.tlib.RouterCreator;
import org.folio.tlib.api.HealthApi;
import org.folio.tlib.api.Tenant2Api;
import org.folio.tlib.postgres.TenantPgPool;

public class MainVerticle extends AbstractVerticle {
  final Logger log = LogManager.getLogger(MainVerticle.class);

  @Override
  public void start(Promise<Void> promise) {
    TenantPgPool.setModule("mod-reservoir");
    ModuleVersionReporter m = new ModuleVersionReporter("com.indexdata/mod-reservoir-server");
    log.info("Starting {} {} {}", m.getModule(), m.getVersion(), m.getCommitId());

    final int port = Integer.parseInt(
        Config.getSysConf("http.port", "port", "8081", config()));
    log.info("Listening on port {}", port);

    ReservoirService reservoirService = new ReservoirService(vertx);

    RouterCreator[] routerCreators = {
        reservoirService,
        new Tenant2Api(reservoirService),
        new HealthApi(),
        new Healthz(),
    };

    RouterCreator.mountAll(vertx, routerCreators, "reservoir")
        .compose(router -> {
          HttpServerOptions so = new HttpServerOptions()
              .setCompressionSupported(true)
              .setDecompressionSupported(true)
              .setHandle100ContinueAutomatically(true)
              .setTcpKeepAlive(true);
          return vertx.createHttpServer(so)
              .requestHandler(router)
              .listen(port).mapEmpty();
        })
        .compose(x ->
          vertx.executeBlocking(() -> {
            JavaScriptCheck.check();
            return null;
          })
        )
        .onComplete(x -> promise.handle(x.mapEmpty()));
  }

  @Override
  public void stop(Promise<Void> promise) {
    TenantPgPool.closeAll()
        .onComplete(promise);
  }
}
