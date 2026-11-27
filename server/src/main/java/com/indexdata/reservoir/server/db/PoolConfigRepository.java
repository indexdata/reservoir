package com.indexdata.reservoir.server.db;

import com.indexdata.reservoir.module.ModuleInvocation;
import com.indexdata.reservoir.server.entity.MatcherArgs;
import com.indexdata.reservoir.server.entity.PoolConfig;
import com.indexdata.reservoir.server.entity.PoolConfigJson;
import com.indexdata.reservoir.server.entity.UpdateMode;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL repository for {@code match_key_config} (pool configurations).
 */
public final class PoolConfigRepository {

  public static final String COLUMNS = "id, matcher, method, params, update, args, cql";

  private final TenantDb db;

  public PoolConfigRepository(TenantDb db) {
    this.db = db;
  }

  private String table() {
    return db.tables().poolConfig();
  }

  /** Map a database row into a PoolConfig domain record. */
  public static PoolConfig fromRow(Row row) {
    return new PoolConfig(
        row.getString("id"),
        MatcherArgs.fromValue(row.getString("args")),
        ModuleInvocation.parseList(row.getString("matcher")),
        row.getString("method"),
        UpdateMode.fromValue(row.getString("update")),
        PoolConfigJson.decodeCql(row.getJsonObject("cql")),
        row.getJsonObject("params"));
  }

  private static Tuple toTuple(PoolConfig config) {
    return Tuple.of(
        config.id(),
        config.matcherString(),
        config.method(),
        config.params(),
        config.update() != null ? config.update().value() : null,
        config.args() != null ? config.args().value() : null,
        PoolConfigJson.encodeCql(config.cql()));
  }

  /** Insert a new pool configuration. */
  public Future<Void> insert(SqlClient client, PoolConfig config) {
    String sql = """
        INSERT INTO %s (id, matcher, method, params, update, args, cql)
        VALUES ($1, $2, $3, $4, $5, $6, $7)
        """.formatted(table());
    return client.preparedQuery(sql)
        .execute(toTuple(config))
        .mapEmpty();
  }

  /** Update an existing pool configuration, returning true if it existed. */
  public Future<Boolean> update(SqlClient client, PoolConfig config) {
    String sql = """
        UPDATE %s
        SET matcher = $2, method = $3, params = $4, update = $5, args = $6, cql = $7
        WHERE id = $1
        """.formatted(table());
    return client.preparedQuery(sql)
        .execute(toTuple(config))
        .map(rows -> rows.rowCount() > 0);
  }

  /**
   * Select a pool configuration by id, or any first pool configuration when {@code id == null}.
   */
  public Future<Optional<PoolConfig>> findById(SqlClient client, String id) {
    SqlBuilder query = SqlBuilder.select(COLUMNS).from(table());
    if (id != null) {
      query.where("id = ?", id);
    }
    SqlBuilder.SqlQuery built = query.build();
    return client.preparedQuery(built.sql())
        .execute(built.params())
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext() ? Optional.of(fromRow(iterator.next())) : Optional.empty();
        });
  }

  /** Load all pool configurations for this tenant. */
  public Future<List<PoolConfig>> findAll(SqlClient client) {
    String sql = "SELECT " + COLUMNS + " FROM " + table();
    return client.query(sql)
        .execute()
        .map(rows -> {
          List<PoolConfig> configs = new ArrayList<>();
          rows.forEach(row -> configs.add(fromRow(row)));
          return configs;
        });
  }

  /** Delete a pool configuration by id, returning true if it existed. */
  public Future<Boolean> delete(SqlClient client, String id) {
    String sql = "DELETE FROM " + table() + " WHERE id = $1";
    return client.preparedQuery(sql)
        .execute(Tuple.of(id))
        .map(rows -> rows.rowCount() > 0);
  }
}
