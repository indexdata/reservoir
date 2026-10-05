package com.indexdata.reservoir.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.sqlclient.Tuple;
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
}
