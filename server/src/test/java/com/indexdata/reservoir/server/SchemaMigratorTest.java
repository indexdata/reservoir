package com.indexdata.reservoir.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.indexdata.reservoir.server.db.OaiPmhClientRepository;
import com.indexdata.reservoir.server.db.PoolConfigRepository;
import com.indexdata.reservoir.server.db.SchemaMigrator;
import com.indexdata.reservoir.server.db.Tables;
import com.indexdata.reservoir.server.db.TenantDb;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class SchemaMigratorTest extends TestBase {

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
  }

  private TenantDb isolatedDb(String schema) {
    Storage base = new Storage(vertx, TENANT_1, HttpMethod.POST);
    return new TenantDb(base.db().pool(), schema, new Tables(schema));
  }

  @Test
  public void concurrentMigrationsSerializeCleanly() throws Exception {
    String schema = "mig_concurrent_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    TenantDb db = isolatedDb(schema);
    await(db.pool().query("CREATE SCHEMA " + schema).execute());
    try {
      List<Future<Void>> runs = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        runs.add(SchemaMigrator.migrate(db));
      }
      await(Future.all(runs));

      var rows = await(db.pool().query(
          "SELECT version FROM " + db.tables().schemaMigrations() + " ORDER BY version").execute());
      assertEquals(2, rows.size());
      var it = rows.iterator();
      assertEquals(Integer.valueOf(1), it.next().getInteger("version"));
      assertEquals(Integer.valueOf(2), it.next().getInteger("version"));
    } finally {
      await(db.pool().query("DROP SCHEMA IF EXISTS " + schema + " CASCADE").execute());
    }
  }

  @Test
  public void upgradesLegacyPreMigrationSchemaAndIsIdempotent() throws Exception {
    String schema = "mig_legacy_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    TenantDb db = isolatedDb(schema);
    await(db.pool().query("CREATE SCHEMA " + schema).execute());
    try {
      // Simulate pre-migration schema layout with existing rows and legacy idx_local_id index.
      String legacySql = """
          SET search_path TO %1$s;
          CREATE TABLE %1$s.global_records (
            id uuid NOT NULL PRIMARY KEY,
            local_id VARCHAR NOT NULL,
            source_id VARCHAR NOT NULL,
            payload JSONB NOT NULL
          );
          CREATE INDEX idx_local_id ON %1$s.global_records (local_id);
          INSERT INTO %1$s.global_records (id, local_id, source_id, payload)
          VALUES ('00000000-0000-0000-0000-000000000001', 'loc1', 'src1', '{}'::jsonb);

          CREATE TABLE %1$s.match_key_config (
            id VARCHAR NOT NULL PRIMARY KEY,
            method VARCHAR,
            update VARCHAR,
            params JSONB
          );
          INSERT INTO %1$s.match_key_config (id, method) VALUES ('p1', 'isbn');

          CREATE TABLE %1$s.module (
            id VARCHAR NOT NULL PRIMARY KEY,
            url VARCHAR,
            function VARCHAR
          );
          INSERT INTO %1$s.module (id, url) VALUES ('m1', 'https://example.org/m.mjs');

          CREATE TABLE %1$s.oai_pmh_clients (
            id VARCHAR NOT NULL PRIMARY KEY,
            config JSONB,
            job JSONB,
            stop BOOLEAN,
            owner UUID
          );
          INSERT INTO %1$s.oai_pmh_clients (id, config) VALUES ('c1', '{}'::jsonb);
          """.formatted(schema);
      await(db.pool().query(legacySql).execute());

      await(SchemaMigrator.migrate(db));

      // Existing row received default source_version = 1.
      Row recRow = await(db.pool().query(
          "SELECT source_version FROM " + db.tables().globalRecords()).execute())
          .iterator().next();
      assertEquals(Integer.valueOf(1), recRow.getInteger("source_version"));

      // Legacy idx_local_id was dropped in this schema.
      Row idxRow = await(db.pool().preparedQuery(
          "SELECT count(*) AS cnt FROM pg_indexes"
              + " WHERE schemaname = $1 AND indexname = 'idx_local_id'")
          .execute(Tuple.of(schema))).iterator().next();
      assertEquals(Long.valueOf(0L), idxRow.getLong("cnt"));

      // Running migrate again is an idempotent no-op.
      await(SchemaMigrator.migrate(db));
      var migRows = await(db.pool().query(
          "SELECT version FROM " + db.tables().schemaMigrations()).execute());
      assertEquals(2, migRows.size());
    } finally {
      await(db.pool().query("DROP SCHEMA IF EXISTS " + schema + " CASCADE").execute());
    }
  }

  @Test
  public void v002NormalizesLegacyPoolMatchers() throws Exception {
    String schema = "mig_v002_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    TenantDb db = isolatedDb(schema);
    await(db.pool().query("CREATE SCHEMA " + schema).execute());
    try {
      await(SchemaMigrator.migrate(db));

      // Roll back only V002 marker and insert legacy unnormalized matcher rows as if on V001.
      await(db.pool().query(
          "DELETE FROM " + db.tables().schemaMigrations() + " WHERE version = 2").execute());
      await(db.pool().preparedQuery(
              "INSERT INTO " + db.tables().poolConfig() + " (id, matcher) VALUES"
                  + " ('legacy', ' a , b:: '), ('clean', 'x,y::fn')")
          .execute());

      await(SchemaMigrator.migrate(db));

      Row legacyRow = await(db.pool().preparedQuery(
              "SELECT matcher FROM " + db.tables().poolConfig() + " WHERE id = $1")
          .execute(Tuple.of("legacy"))).iterator().next();
      assertEquals("a,b", legacyRow.getString("matcher"));

      Row exactMatch = await(db.pool().preparedQuery(
              "SELECT id FROM " + db.tables().poolConfig() + " WHERE matcher = $1")
          .execute(Tuple.of("a,b"))).iterator().next();
      assertEquals("legacy", exactMatch.getString("id"));

      Row cleanRow = await(db.pool().preparedQuery(
              "SELECT matcher FROM " + db.tables().poolConfig() + " WHERE id = $1")
          .execute(Tuple.of("clean"))).iterator().next();
      assertEquals("x,y::fn", cleanRow.getString("matcher"));
    } finally {
      await(db.pool().query("DROP SCHEMA IF EXISTS " + schema + " CASCADE").execute());
    }
  }

  @Test
  public void strictRowDecodingRejectsCorruptPoolConfigAndStatus() throws Exception {
    String schema = "mig_strict_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    TenantDb db = isolatedDb(schema);
    await(db.pool().query("CREATE SCHEMA " + schema).execute());
    try {
      await(SchemaMigrator.migrate(db));

      await(db.pool().preparedQuery(
              "INSERT INTO " + db.tables().poolConfig()
                  + " (id, matcher, args) VALUES ($1, $2, $3)")
          .execute(Tuple.of("bad-pool", "m1", "unknown-args")));

      // A null cql mapping (disabled index) is valid stored data and must still load.
      await(db.pool().preparedQuery(
              "INSERT INTO " + db.tables().poolConfig()
                  + " (id, matcher, cql) VALUES ($1, $2, $3)")
          .execute(Tuple.of("null-cql-pool", "m1",
              new JsonObject().putNull("disabled").put("isbn", "m1"))));
      PoolConfigRepository poolRepo = new PoolConfigRepository(db);
      var nullCqlPool = await(poolRepo.findById(db.client(), "null-cql-pool")).orElseThrow();
      assertEquals(List.of("isbn"), new ArrayList<>(nullCqlPool.cql().keySet()));

      ExecutionException poolErr = assertThrows(
          ExecutionException.class, () -> await(poolRepo.findAll(db.client())));
      assertTrue(poolErr.getCause() instanceof IllegalArgumentException);

      await(db.pool().preparedQuery(
              "INSERT INTO " + db.tables().oaiPmhClients()
                  + " (id, config, job) VALUES ($1, $2, $3)")
          .execute(Tuple.of("bad-client", new JsonObject().put("url", "http://x"),
              new JsonObject().put("status", "unknown-state"))));

      OaiPmhClientRepository clientRepo = new OaiPmhClientRepository(db);
      ExecutionException clientErr = assertThrows(
          ExecutionException.class, () -> await(clientRepo.findAll(db.client())));
      assertTrue(clientErr.getCause() instanceof IllegalArgumentException);
    } finally {
      await(db.pool().query("DROP SCHEMA IF EXISTS " + schema + " CASCADE").execute());
    }
  }
}
