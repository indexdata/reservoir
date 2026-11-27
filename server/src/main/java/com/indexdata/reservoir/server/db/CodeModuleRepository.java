package com.indexdata.reservoir.server.db;

import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL repository for {@code module} (code modules).
 */
public final class CodeModuleRepository {

  public static final String COLUMNS = "id, type, url, function, script";

  private final TenantDb db;

  public CodeModuleRepository(TenantDb db) {
    this.db = db;
  }

  private String table() {
    return db.tables().module();
  }

  /** Map a database row into a CodeModuleEntity record. */
  public static CodeModuleEntity fromRow(Row row) {
    return new CodeModuleEntity(
        row.getString("id"),
        row.getString("type"),
        row.getString("url"),
        row.getString("function"),
        row.getString("script"));
  }

  private static Tuple toTuple(CodeModuleEntity module) {
    return Tuple.of(
        module.id(),
        module.type(),
        module.url(),
        module.function(),
        module.script());
  }

  /** Insert a new code module. */
  public Future<Void> insert(SqlClient client, CodeModuleEntity module) {
    String sql = """
        INSERT INTO %s (id, type, url, function, script)
        VALUES ($1, $2, $3, $4, $5)
        """.formatted(table());
    return client.preparedQuery(sql)
        .execute(toTuple(module))
        .mapEmpty();
  }

  /** Update an existing code module, returning true if it existed. */
  public Future<Boolean> update(SqlClient client, CodeModuleEntity module) {
    String sql = """
        UPDATE %s
        SET type = $2, url = $3, function = $4, script = $5
        WHERE id = $1
        """.formatted(table());
    return client.preparedQuery(sql)
        .execute(toTuple(module))
        .map(rows -> rows.rowCount() > 0);
  }

  /**
   * Select a code module by id, or any first code module when {@code id == null}.
   */
  public Future<Optional<CodeModuleEntity>> findById(SqlClient client, String id) {
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

  /** Load all code modules for this tenant. */
  public Future<List<CodeModuleEntity>> findAll(SqlClient client) {
    String sql = "SELECT " + COLUMNS + " FROM " + table();
    return client.query(sql)
        .execute()
        .map(rows -> {
          List<CodeModuleEntity> modules = new ArrayList<>();
          rows.forEach(row -> modules.add(fromRow(row)));
          return modules;
        });
  }

  /** Delete a code module by id, returning true if it existed. */
  public Future<Boolean> delete(SqlClient client, String id) {
    String sql = "DELETE FROM " + table() + " WHERE id = $1";
    return client.preparedQuery(sql)
        .execute(Tuple.of(id))
        .map(rows -> rows.rowCount() > 0);
  }
}
