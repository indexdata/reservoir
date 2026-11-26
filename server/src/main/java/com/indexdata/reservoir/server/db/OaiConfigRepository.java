package com.indexdata.reservoir.server.db;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.Optional;

/**
 * PostgreSQL repository for {@code oai_config}.
 */
public final class OaiConfigRepository {

  private static final String SINGLETON_ID = "1";

  private final TenantDb db;

  public OaiConfigRepository(TenantDb db) {
    this.db = db;
  }

  private String table() {
    return db.tables().oaiConfig();
  }

  /** Insert or update the singleton OAI configuration. */
  public Future<Void> upsert(SqlClient client, JsonObject config) {
    String sql = """
        INSERT INTO %s (id, config)
        VALUES ($1, $2)
        ON CONFLICT (id) DO UPDATE SET config = $2
        """.formatted(table());
    return client.preparedQuery(sql)
        .execute(Tuple.of(SINGLETON_ID, config))
        .mapEmpty();
  }

  /** Fetch the singleton OAI configuration if present. */
  public Future<Optional<JsonObject>> find(SqlClient client) {
    String sql = "SELECT config FROM " + table() + " WHERE id = $1";
    return client.preparedQuery(sql)
        .execute(Tuple.of(SINGLETON_ID))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext()
              ? Optional.ofNullable(iterator.next().getJsonObject("config"))
              : Optional.empty();
        });
  }

  /** Delete the singleton OAI configuration. */
  public Future<Void> delete(SqlClient client) {
    String sql = "DELETE FROM " + table() + " WHERE id = $1";
    return client.preparedQuery(sql)
        .execute(Tuple.of(SINGLETON_ID))
        .mapEmpty();
  }
}
