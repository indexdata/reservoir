package com.indexdata.reservoir.server;

import com.indexdata.reservoir.server.metrics.IngestMetrics;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class InitializationDiagnosticsDatabaseTest extends TestBase {
  @Test
  public void explainPreservesQueryResults(TestContext context) {
    Storage storage = new Storage(vertx, TENANT_1, HttpMethod.POST);
    var diagnostics = new InitializationDiagnostics(TENANT_1, "pool", "job", 1, 1, 100);
    storage.pool.withTransaction(connection ->
        diagnostics.explain(connection, "SELECT $1::text AS value", Tuple.of("match"), 1)
            .compose(ignored -> diagnostics.explain(connection,
                "SELECT nonexistent_column", Tuple.tuple(), 1))
            .compose(ignored -> connection.query("SELECT 42 AS answer").execute())
            .map(rows -> {
              context.assertEquals(42, rows.iterator().next().getInteger("answer"));
              context.assertEquals(1, diagnostics.snapshot("test").getInteger("plans"));
              return (Void) null;
            }))
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void explainFailureIsReported(TestContext context) {
    Storage storage = new Storage(vertx, TENANT_1, HttpMethod.POST);
    var diagnostics = new InitializationDiagnostics(TENANT_1, "pool", "job", 1, 1, 100);
    storage.pool.withTransaction(connection -> diagnostics.explain(connection,
        "SELECT nonexistent_column", Tuple.tuple(), 1))
        .onComplete(context.asyncAssertFailure(error -> {
          context.assertTrue(error.getMessage().contains("nonexistent_column"));
          context.assertEquals(1L, diagnostics.snapshot("test").getJsonObject("stages")
              .getJsonObject("explain").getLong("failures"));
        }));
  }

  @Test
  public void synchronousRecordFailureRollsBackAndStops(TestContext context) {
    synchronousFailure(context, false, 2);
  }

  @Test
  public void synchronousExplainFailureRollsBackAndStops(TestContext context) {
    synchronousFailure(context, true, 2);
  }

  @Test
  public void synchronousLastRecordFailureCannotCommit(TestContext context) {
    synchronousFailure(context, true, 3);
  }

  private void synchronousFailure(TestContext context, boolean sqlFailure, int failAt) {
    AtomicInteger processed = new AtomicInteger();
    RuntimeException failure = new RuntimeException("matcher failed");
    String writes = "initialization_writes_" + UUID.randomUUID().toString().replace("-", "");
    var diagnostics = new InitializationDiagnostics(TENANT_1, "pool", "job", 1, 1, 100);
    Storage storage = new Storage(vertx, TENANT_1, HttpMethod.POST) {
      @Override
      Future<Void> initializeRecord(SqlConnection connection, IngestMatcher matcher,
          IngestMetrics metrics, UUID globalId, JsonObject record) {
        int count = processed.incrementAndGet();
        return connection.query("INSERT INTO " + writes + " VALUES (1)").execute()
            .compose(ignored -> {
              if (count != failAt) {
                return Future.succeededFuture();
              }
              return sqlFailure ? diagnostics.explain(connection,
                  "SELECT nonexistent_column", Tuple.tuple(), 1) : Future.failedFuture(failure);
            });
      }
    };
    storage.pool.withConnection(connection -> connection.query(
        "CREATE TEMP TABLE " + writes + " (value integer)").execute()
        .compose(ignored -> connection.query("DELETE FROM " + storage.globalRecordTable).execute())
        .compose(ignored -> connection.query("INSERT INTO " + storage.globalRecordTable
            + " (id, local_id, source_id, payload)"
            + " SELECT gen_random_uuid(), n::text, 'diagnostics-test', '{}'::jsonb"
            + " FROM generate_series(1, 3) n").execute())
        .compose(ignored -> storage.recalculateMatchKeyValueTable(connection, new IngestMatcher()))
        .transform(result -> {
          context.assertTrue(result.failed(), "Initialization must fail, not return a record count");
          context.assertEquals(failAt, processed.get(), "No records after the failure may run");
          if (sqlFailure) {
            context.assertTrue(result.cause().getMessage().contains("nonexistent_column"));
          } else {
            context.assertTrue(result.cause() == failure, "Preserve the original failure");
          }
          return connection.query("SELECT count(*) AS count FROM " + writes).execute();
        })
        .compose(rows -> {
          context.assertEquals(0L, rows.iterator().next().getLong("count"),
              "All writes preceding the failure must roll back");
          return connection.query("DROP TABLE " + writes).execute();
        }))
        .onComplete(context.asyncAssertSuccess());
  }

}
