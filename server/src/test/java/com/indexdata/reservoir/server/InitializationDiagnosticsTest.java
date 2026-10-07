package com.indexdata.reservoir.server;

import static org.junit.Assert.*;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class InitializationDiagnosticsTest {
  @Test
  public void disabledByDefaultIncludingPlanSettings() {
    assertNull(InitializationDiagnostics.create(Map.of(), "t", "p", "j", null));
    assertNull(InitializationDiagnostics.create(Map.of("RESERVOIR_INIT_EXPLAIN_MAX", "20"),
        "t", "p", "j", null));
    assertNull(InitializationDiagnostics.create(Map.of("RESERVOIR_INIT_DIAGNOSTICS", "false"),
        "t", "p", "j", null));
  }

  @Test
  public void metricsWithoutDiagnosticsAreBoundedAndPreserveAttemptSemantics() {
    var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    try {
      var diagnostics = InitializationDiagnostics.create(
          Map.of("RESERVOIR_INIT_EXPLAIN_MAX", "20"), "tenant", "pool", "job", registry);
      assertNotNull(diagnostics);
      assertFalse(diagnostics.samplePlan());
      assertTrue(diagnostics.explain(null, null, null, 37).succeeded());
      diagnostics.measure("lookup.keys=37", () -> Future.succeededFuture());
      diagnostics.measure("lookup.keys=123", () -> Future.failedFuture("rolled back"));
      diagnostics.rows("lookup.keys=37", 3);
      diagnostics.rows("lookup.keys=123", 7);
      diagnostics.keys(0);
      diagnostics.keys(37);
      diagnostics.clusters(2);
      diagnostics.recordDone(true);
      diagnostics.recordDone(false);
      diagnostics.summary("stopped");
      assertEquals(1, registry.get("reservoir_initialization_stage_duration_seconds")
          .tags("stage", "lookup", "outcome", "success").timer().count());
      assertEquals(1, registry.get("reservoir_initialization_stage_duration_seconds")
          .tags("stage", "lookup", "outcome", "failure").timer().count());
      assertEquals(10, registry.get("reservoir_initialization_rows_total")
          .tag("stage", "lookup").counter().count(), 0);
      assertEquals(1, registry.get("reservoir_initialization_record_attempts_total")
          .tag("outcome", "success").counter().count(), 0);
      assertEquals(1, registry.get("reservoir_initialization_record_attempts_total")
          .tag("outcome", "failure").counter().count(), 0);
      var keys = registry.get("reservoir_initialization_keys_per_record").summary();
      assertEquals(2, keys.count());
      assertEquals(37, keys.totalAmount(), 0);
      assertEquals(11, keys.takeSnapshot().histogramCounts().length);
      assertEquals(1, keys.takeSnapshot().histogramCounts()[0].count(), 0);
      assertEquals(2, registry.get("reservoir_initialization_clusters_found_total")
          .counter().count(), 0);
      registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
          assertTrue(tag.getKey().equals("stage") || tag.getKey().equals("outcome"))));
      String scrape = registry.scrape();
      assertTrue(scrape.contains("reservoir_initialization_keys_per_record_bucket{le=\"0.5\"} 1"));
      assertTrue(scrape.contains("reservoir_initialization_stage_duration_seconds_bucket"));
      assertFalse(scrape.contains("lookup.keys="));
      assertFalse(scrape.contains("job="));
      assertFalse(scrape.contains("completed"));
      int meterCount = registry.getMeters().size();
      var resumed = InitializationDiagnostics.create(Map.of(), "other", "other", "new", registry);
      resumed.recordDone(true);
      resumed.summary("stopped");
      assertEquals(meterCount, registry.getMeters().size());
      assertEquals(2, registry.get("reservoir_initialization_record_attempts_total")
          .tag("outcome", "success").counter().count(), 0);
    } finally {
      registry.close();
    }
  }

  @Test
  public void timingAloneDoesNotExplain() {
    var diagnostics = InitializationDiagnostics.create(
        Map.of("RESERVOIR_INIT_DIAGNOSTICS", "true"), "t", "p", "j");
    assertFalse(diagnostics.samplePlan());
    assertTrue(diagnostics.explain(null, null, null, 0).succeeded());
  }

  @Test
  public void sampleAcrossRecordsWithCap() {
    var diagnostics = InitializationDiagnostics.create(Map.of(
        "RESERVOIR_INIT_DIAGNOSTICS", "true", "RESERVOIR_INIT_EXPLAIN_MAX", "2",
        "RESERVOIR_INIT_EXPLAIN_EVERY", "3"), "t", "p", "j");
    for (int record = 0; record < 10; record++) {
      assertEquals(record == 0 || record == 3, diagnostics.samplePlan());
      diagnostics.recordDone(true);
    }
    assertEquals(2, diagnostics.snapshot("test").getInteger("plans").intValue());
  }

  @Test
  public void invalidSettingsFallBackSafely() {
    var diagnostics = InitializationDiagnostics.create(Map.of(
        "RESERVOIR_INIT_DIAGNOSTICS", "true", "RESERVOIR_INIT_EXPLAIN_MAX", "-1",
        "RESERVOIR_INIT_EXPLAIN_EVERY", "0", "RESERVOIR_INIT_SUMMARY_EVERY", "invalid"),
        "t", "p", "j");
    assertFalse(diagnostics.samplePlan());
    diagnostics.recordDone(true);
  }

  @Test
  public void measuresCompletionAndPreservesFailures() throws Exception {
    var diagnostics = new InitializationDiagnostics("t", "p", "j", 10, 0, 100);
    Promise<String> pending = Promise.promise();
    Future<String> result = diagnostics.measure("matcher", pending::future);
    assertFalse(result.isComplete());
    pending.complete("value");
    assertEquals("value", result.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
    RuntimeException failure = new RuntimeException("failed");
    Future<Object> failed = diagnostics.measure("matcher", () -> Future.failedFuture(failure));
    assertSame(failure, failed.cause());
    Future<Object> thrown = diagnostics.measure("matcher", () -> { throw failure; });
    assertSame(failure, thrown.cause());
    diagnostics.keys(3);
    diagnostics.rows("matcher", 2);
    diagnostics.recordDone(false);
    JsonObject snapshot = diagnostics.snapshot("test");
    JsonObject stage = snapshot.getJsonObject("stages").getJsonObject("matcher");
    assertEquals(3L, stage.getLong("calls").longValue());
    assertEquals(2L, stage.getLong("failures").longValue());
    assertEquals(2L, stage.getLong("rows").longValue());
    assertEquals(1L, snapshot.getJsonObject("keysPerRecord").getLong("3").longValue());
    assertEquals(1L, snapshot.getLong("failedRecords").longValue());
  }
}
