package com.indexdata.reservoir.server;

import static org.junit.Assert.*;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class InitializationDiagnosticsTest {
  @Test
  public void disabledByDefaultIncludingPlanSettings() {
    assertNull(InitializationDiagnostics.create(Map.of(), "t", "p", "j"));
    assertNull(InitializationDiagnostics.create(Map.of("RESERVOIR_INIT_EXPLAIN_MAX", "20"),
        "t", "p", "j"));
    assertNull(InitializationDiagnostics.create(Map.of("RESERVOIR_INIT_DIAGNOSTICS", "false"),
        "t", "p", "j"));
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
