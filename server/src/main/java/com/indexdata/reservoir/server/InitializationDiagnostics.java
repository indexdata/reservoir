package com.indexdata.reservoir.server;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Bounded, opt-in diagnostics owned by one initialization execution. */
class InitializationDiagnostics {
  private static final Logger log = LogManager.getLogger(InitializationDiagnostics.class);
  private final JsonObject context;
  private final int every;
  private final int maxPlans;
  private final int summaryEvery;
  private final long started = System.nanoTime();
  private final Map<String, Timing> timings = new LinkedHashMap<>();
  private final Map<Integer, Long> keyCounts = new LinkedHashMap<>();
  private long records;
  private long failures;
  private long clustersFound;
  private int plans;

  private static class Timing {
    long calls;
    long nanos;
    long maxNanos;
    long rows;
    long failures;
  }

  static InitializationDiagnostics create(String tenant, String pool, String job) {
    return create(System.getenv(), tenant, pool, job);
  }

  static InitializationDiagnostics create(Map<String, String> env, String tenant,
      String pool, String job) {
    if (!Boolean.parseBoolean(env.get("RESERVOIR_INIT_DIAGNOSTICS"))) {
      return null;
    }
    return new InitializationDiagnostics(tenant, pool, job,
        setting(env, "RESERVOIR_INIT_EXPLAIN_EVERY", 5000, 1),
        setting(env, "RESERVOIR_INIT_EXPLAIN_MAX", 0, 0),
        setting(env, "RESERVOIR_INIT_SUMMARY_EVERY", 5000, 1));
  }

  private static int setting(Map<String, String> env, String name, int fallback, int minimum) {
    String value = env.get(name);
    if (value == null) {
      return fallback;
    }
    try {
      int result = Integer.parseInt(value);
      if (result >= minimum) {
        return result;
      }
    } catch (NumberFormatException e) {
      // Use the safe default below.
    }
    log.warn("Invalid {}={}, using {}", name, value, fallback);
    return fallback;
  }

  InitializationDiagnostics(String tenant, String pool, String job, int every,
      int maxPlans, int summaryEvery) {
    context = new JsonObject().put("tenant", tenant).put("pool", pool).put("job", job);
    this.every = every;
    this.maxPlans = maxPlans;
    this.summaryEvery = summaryEvery;
  }

  <T> Future<T> measure(String stage, Supplier<Future<T>> action) {
    long start = System.nanoTime();
    Future<T> future;
    try {
      future = action.get();
    } catch (Exception e) {
      future = Future.failedFuture(e);
    }
    return future.transform(result -> {
      elapsed(stage, start, result.succeeded());
      return result.succeeded() ? Future.succeededFuture(result.result())
          : Future.failedFuture(result.cause());
    });
  }

  void elapsed(String stage, long start, boolean success) {
    Timing timing = timings.computeIfAbsent(stage, ignored -> new Timing());
    long elapsed = System.nanoTime() - start;
    timing.calls++;
    timing.nanos += elapsed;
    timing.maxNanos = Math.max(timing.maxNanos, elapsed);
    if (!success) {
      timing.failures++;
    }
  }

  void rows(String stage, int count) {
    timings.computeIfAbsent(stage, ignored -> new Timing()).rows += count;
  }

  void keys(int count) {
    keyCounts.merge(count, 1L, Long::sum);
  }

  void clusters(int count) {
    clustersFound += count;
  }

  void recordDone(boolean success) {
    records++;
    if (!success) {
      failures++;
    }
    if (records % summaryEvery == 0) {
      summary("progress");
    }
  }

  boolean samplePlan() {
    if (plans >= maxPlans || records % every != 0) {
      return false;
    }
    plans++;
    return true;
  }

  Future<Void> explain(SqlConnection connection, String sql, Tuple parameters, int keys) {
    if (!samplePlan()) {
      return Future.succeededFuture();
    }
    return this.<Void>measure("explain", () -> connection.preparedQuery(
        "EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, FORMAT JSON) " + sql)
        .execute(parameters)
        .map(rows -> {
          log.info("Pool initialization plan {}", context.copy()
              .put("record", records + 1).put("keys", keys)
              .put("plan", rows.iterator().next().getValue(0)).encode());
          return (Void) null;
        }))
        .onFailure(error -> log.warn("Pool initialization plan failed {}",
            context.encode(), error));
  }

  JsonObject snapshot(String status) {
    JsonObject stages = new JsonObject();
    timings.forEach((name, timing) -> stages.put(name, new JsonObject()
        .put("calls", timing.calls).put("totalMs", timing.nanos / 1_000_000.0)
        .put("meanMs", timing.calls == 0 ? 0 : timing.nanos / 1_000_000.0 / timing.calls)
        .put("maxMs", timing.maxNanos / 1_000_000.0)
        .put("rows", timing.rows).put("failures", timing.failures)));
    JsonObject keys = new JsonObject();
    keyCounts.forEach((count, calls) -> keys.put(count.toString(), calls));
    return context.copy().put("status", status).put("records", records)
        .put("failedRecords", failures)
        .put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0)
        .put("plans", plans).put("keysPerRecord", keys)
        .put("clustersFound", clustersFound).put("stages", stages);
  }

  void summary(String status) {
    log.info("Pool initialization diagnostics {}", snapshot(status).encode());
  }
}
