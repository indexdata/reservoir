package org.folio.reservoir.server;

import io.vertx.core.Launcher;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxJmxMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.Config;

public class ReservoirLauncher extends Launcher {
  final Logger log = LogManager.getLogger(ReservoirLauncher.class);
  private static final String PROMETHEUS_PORT = "metrics.prometheus.port";
  private static final String PROMETHEUS_PATH = "/metrics";
  private static final String JMX_ENABLED = "metrics.jmx";
  private static final String JMX_DOMAIN = "reservoir";
  private JsonObject config;

  public static void main(String[] args) {
    new ReservoirLauncher().dispatch(args);
  }

  @Override
  public void afterConfigParsed(JsonObject config) {
    this.config = config;
    super.afterConfigParsed(config);
  }

  @Override
  public void beforeStartingVertx(VertxOptions options) {
    boolean enabled = false;
    MicrometerMetricsOptions metricsOpts = new MicrometerMetricsOptions();
    final int promPort = Config.getSysConfInteger(PROMETHEUS_PORT, -1, config);
    if (promPort != -1) {
      log.info("Enabling Prometheus metrics at {}:{}", PROMETHEUS_PATH, promPort);
      enabled = true;
      metricsOpts.setPrometheusOptions(new VertxPrometheusOptions()
          .setEnabled(true)
          .setStartEmbeddedServer(true)
          .setEmbeddedServerOptions(new HttpServerOptions().setPort(promPort))
          .setEmbeddedServerEndpoint(PROMETHEUS_PATH));
    }
    final boolean jmxEnabled = Config.getSysConfBoolean(JMX_ENABLED, false, config);
    if (jmxEnabled) {
      log.info("Enabling JMX metrics for domain '{}'", JMX_DOMAIN);
      enabled = true;
      metricsOpts.setJmxMetricsOptions(new VertxJmxMetricsOptions()
          .setEnabled(true)
          .setStep(5)
          .setDomain(JMX_DOMAIN));
    }
    metricsOpts.setEnabled(enabled);
    options.setMetricsOptions(metricsOpts);
    super.beforeStartingVertx(options);
  }

}
