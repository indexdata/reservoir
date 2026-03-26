package com.indexdata.reservoir.server;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.Config;
import org.folio.okapi.common.ModuleVersionReporter;
import org.folio.tlib.RouterCreator;
import org.folio.tlib.api.HealthApi;
import org.folio.tlib.api.Tenant2Api;
import org.folio.tlib.postgres.TenantPgPool;

public class MainVerticle extends VerticleBase {
  final Logger log = LogManager.getLogger(MainVerticle.class);

  @Override
  public Future<?> start() {
    System.setProperty("org.marc4j.marc.MarcFactory", "org.marc4j.marc.impl.MarcFactoryImpl");
    TenantPgPool.setModule("mod-reservoir");
    ModuleVersionReporter m = new ModuleVersionReporter("com.indexdata/reservoir-server");
    log.info("Starting {} {} {}", m.getModule(), m.getVersion(), m.getCommitId());

    final int port = Integer.parseInt(
        Config.getSysConf("http.port", "port", "8081", config()));
    log.info("Listening on port {}", port);

    String tenantDefault = ReservoirLauncher.getSysConfOrEnvString(
        "tenant.default", null, config());
    Future<Void> future = Future.succeededFuture();
    if (tenantDefault != null) {
      log.info("Tenant default: {}", tenantDefault);
    }
    ReservoirService reservoirService = new ReservoirService(m, tenantDefault);

    RouterCreator[] routerCreators = {
        reservoirService,
        new Tenant2Api(reservoirService),
        new HealthApi(),
        new Healthz(),
    };
    if (Config.getSysConfBoolean("db_check", true, config())) {
      future = future.compose(x -> Healthz.checkDb(vertx));
    }
    if (tenantDefault != null) {
      Storage storage = new Storage(vertx, tenantDefault, HttpMethod.POST);
      future = future.compose(x -> storage.init());
    }
    return future
        .compose(x -> RouterCreator.mountAll(vertx, routerCreators, "reservoir"))
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
        );
  }

  @Override
  public Future<?> stop() {
    return TenantPgPool.closeAll();
  }
}
