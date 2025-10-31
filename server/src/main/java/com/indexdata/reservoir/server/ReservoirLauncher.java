package com.indexdata.reservoir.server;

import io.vertx.core.Deployable;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.launcher.application.HookContext;
import io.vertx.launcher.application.VertxApplication;
import io.vertx.launcher.application.VertxApplicationHooks;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxJmxMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.Config;

public class ReservoirLauncher extends VertxApplication {
  private static final Logger log = LogManager.getLogger(ReservoirLauncher.class);
  private static final String PROMETHEUS_PORT_KEY = "metrics.prometheus.port";
  private static final String PROMETHEUS_PATH_KEY = "metrics.prometheus.path";
  private static final String PROMETHEUS_PATH_DEF = "/metrics";
  private static final String JMX_ENABLED_KEY = "metrics.jmx";
  private static final String JMX_DOMAIN_KEY = "metrics.jmx.domain";
  private static final String JMX_DOMAIN_DEF = "reservoir";
  static Function<String,String> envLookup = System::getenv;

  public ReservoirLauncher(String[] args, VertxApplicationHooks hooks) {
    super(args, hooks, true, false);
  }

  /** run the application. */
  public static void main(String[] args) {
    int ret = launch(args);
    if (ret != 0) {
      System.exit(ret);
    }
  }

  static int launch(String[] args) {
    System.setProperty("org.marc4j.marc.MarcFactory", "org.marc4j.marc.impl.MarcFactoryImpl");
    VertxApplicationHooks hooks = new VertxApplicationHooks() {
      @Override
      public void beforeStartingVertx(HookContext context) {
        final VertxOptions options = context.vertxOptions();
        JsonObject config = options.toJson();
        boolean enabled = false;
        MicrometerMetricsOptions metricsOpts = new MicrometerMetricsOptions();
        final int promPort = getSysConfOrEnvInteger(PROMETHEUS_PORT_KEY, -1, config);
        if (promPort > 0) {
          String promPath = getSysConfOrEnvString(PROMETHEUS_PATH_KEY, PROMETHEUS_PATH_DEF, config);
          log.info("Enabling Prometheus metrics at {}:{}", promPath, promPort);
          enabled = true;
          metricsOpts.setPrometheusOptions(new VertxPrometheusOptions()
              .setEnabled(true)
              .setStartEmbeddedServer(true)
              .setEmbeddedServerOptions(new HttpServerOptions().setPort(promPort))
              .setEmbeddedServerEndpoint(promPath));
        }
        final boolean jmxEnabled = getSysConfOrEnvBoolean(JMX_ENABLED_KEY, false, config);
        if (jmxEnabled) {
          String jmxDomain = getSysConfOrEnvString(JMX_DOMAIN_KEY, JMX_DOMAIN_DEF, config);
          log.info("Enabling JMX metrics for domain '{}'", jmxDomain);
          enabled = true;
          metricsOpts.setJmxMetricsOptions(new VertxJmxMetricsOptions()
              .setEnabled(true)
              .setStep(5)
              .setDomain(jmxDomain));
        }
        metricsOpts.setEnabled(enabled);
        options.setMetricsOptions(metricsOpts);
      }

      @Override
      public Supplier<? extends Deployable> verticleSupplier() {
        return () -> new MainVerticle();
      }
    };
    VertxApplication app = new ReservoirLauncher(args, hooks);
    return app.launch();
  }

  static String getSysConfOrEnvString(String name, String defaultValue, JsonObject config) {
    String v = Config.getSysConf(name, null, config);
    if (v != null) {
      return v;
    }
    String env = envLookup.apply(propToEnv(name));
    if (env != null && !env.isBlank()) {
      return env;
    }
    return defaultValue;
  }

  static Integer getSysConfOrEnvInteger(String name, int defaultValue, JsonObject config) {
    Integer v = Config.getSysConfInteger(name, null, config);
    if (v != null) {
      return v;
    }
    String env = envLookup.apply(propToEnv(name));
    if (env != null && !env.isBlank()) {
      try {
        return Integer.parseInt(env);
      } catch (NumberFormatException e) {
        log.warn("Cannot parse env {}='{}' as int, using default '{}'", name, env, defaultValue);
      }
    }
    return defaultValue;
  }

  static Boolean getSysConfOrEnvBoolean(String name, boolean defaultValue, JsonObject config) {
    Boolean v = Config.getSysConfBoolean(name, null, config);
    if (v != null) {
      return v;
    }
    String env = envLookup.apply(propToEnv(name));
    if (env != null && !env.isBlank()) {
      if ("true".equals(env)) {
        return true;
      }
      if ("false".equals(env)) {
        return false;
      }
      log.warn("Cannot parse env {}='{}' as bool, using default '{}'", name, env, defaultValue);
    }
    return defaultValue;
  }

  static String propToEnv(String sysProp) {
    return sysProp.replace('.', '_').toUpperCase();
  }

}
