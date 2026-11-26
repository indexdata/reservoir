package com.indexdata.reservoir.server.db;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lightweight, native-image-friendly versioned SQL migration runner.
 *
 * <p>Serializes concurrent tenant startups via {@code pg_advisory_xact_lock(hashtext(schema))},
 * sets {@code LOCAL search_path} to the tenant schema, and records applied versions in
 * {@code <schema>.schema_migrations}. Scripts are executed via PostgreSQL's simple query protocol,
 * so multi-statement files and {@code $$}-quoted blocks work natively.
 *
 * <p>To add a new migration:
 * <ol>
 *   <li>Create {@code server/src/main/resources/db/migrations/VNNN__description.sql}. Use
 *       {@code ${schema}} where schema-qualified object names are needed.</li>
 *   <li>Register a new {@link Migration} entry at the end of {@link #MIGRATIONS} with the next
 *       monotonically increasing version number. Never edit or reorder already-applied
 *       scripts.</li>
 * </ol>
 *
 * <p>All files under {@code db/migrations/} are automatically bundled into GraalVM native images
 * via {@code META-INF/native-image/com.indexdata/reservoir-server/resource-config.json}.
 */
public final class SchemaMigrator {

  private static final Logger log = LogManager.getLogger(SchemaMigrator.class);

  private record Migration(int version, String description, String resourcePath) { }

  private static final List<Migration> MIGRATIONS = List.of(
      new Migration(1, "baseline", "db/migrations/V001__baseline.sql"),
      new Migration(2, "normalize_pool_matchers", "db/migrations/V002__normalize_pool_matchers.sql")
  );

  private SchemaMigrator() { }

  /** Apply all unapplied SQL migrations to the tenant schema inside a serialized transaction. */
  public static Future<Void> migrate(TenantDb db) {
    String schema = db.tables().schema();
    String migrationsTable = db.tables().schemaMigrations();
    return db.withTransaction(conn ->
        conn.preparedQuery("SELECT pg_advisory_xact_lock(hashtext($1))")
            .execute(Tuple.of(schema))
            .compose(_ -> conn.query("SET LOCAL search_path TO " + schema).execute())
            .compose(_ -> conn.query("""
                CREATE TABLE IF NOT EXISTS %s (
                  version INTEGER NOT NULL PRIMARY KEY,
                  description VARCHAR NOT NULL,
                  applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(migrationsTable)).execute())
            .compose(_ -> conn.query("SELECT version FROM " + migrationsTable).execute())
            .compose(rows -> {
              Set<Integer> applied = new HashSet<>();
              for (Row row : rows) {
                applied.add(row.getInteger("version"));
              }
              Future<Void> chain = Future.succeededFuture();
              for (Migration migration : MIGRATIONS) {
                if (applied.contains(migration.version())) {
                  continue;
                }
                chain = chain.compose(_ ->
                    applyMigration(conn, schema, migrationsTable, migration));
              }
              return chain;
            }));
  }

  private static Future<Void> applyMigration(SqlConnection conn, String schema,
      String migrationsTable, Migration migration) {
    log.info("Applying migration V{} ({}) to schema {}",
        migration.version(), migration.description(), schema);
    String script = loadResource(migration.resourcePath()).replace("${schema}", schema);
    // Simple query protocol natively executes multi-statement scripts including $$ bodies.
    return conn.query(script).execute()
        .compose(_ -> conn.preparedQuery(
                "INSERT INTO " + migrationsTable + " (version, description) VALUES ($1, $2)")
            .execute(Tuple.of(migration.version(), migration.description()))
            .mapEmpty());
  }

  private static String loadResource(String path) {
    try (InputStream in = SchemaMigrator.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Migration resource not found: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to read migration resource: " + path, e);
    }
  }
}
