package com.indexdata.reservoir.server.db;

import com.indexdata.reservoir.server.entity.GlobalRecord;
import com.indexdata.reservoir.util.SourceId;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL repository for {@code global_records}.
 */
public final class GlobalRecordRepository {

  public static final String COLUMNS = "id, local_id, source_id, source_version, payload";

  private final TenantDb db;

  public GlobalRecordRepository(TenantDb db) {
    this.db = db;
  }

  private String table() {
    return db.tables().globalRecords();
  }

  /** Map a database row into a GlobalRecord domain object. */
  public static GlobalRecord fromRow(Row row) {
    Integer version = row.getInteger("source_version");
    return new GlobalRecord(
        row.getUUID("id"),
        row.getString("local_id"),
        row.getString("source_id"),
        version != null ? version : 1,
        row.getJsonObject("payload"));
  }

  /** Insert or update a global record by natural key, returning its UUID. */
  public Future<UUID> upsert(SqlClient client, UUID candidateId, String localId,
      SourceId sourceId, int sourceVersion, JsonObject payload) {
    String sql = """
        INSERT INTO %s (id, local_id, source_id, source_version, payload)
        VALUES ($1, $2, $3, $4, $5)
        ON CONFLICT (local_id, source_id, source_version) DO UPDATE
        SET payload = $5
        RETURNING id
        """.formatted(table());
    return client.preparedQuery(sql)
        .execute(Tuple.of(candidateId, localId, sourceId.toString(), sourceVersion, payload))
        .map(rows -> rows.iterator().next().getUUID("id"));
  }

  /** Find a global record by its UUID string identifier. */
  public Future<Optional<GlobalRecord>> findById(SqlClient client, String id) {
    String sql = "SELECT " + COLUMNS + " FROM " + table() + " WHERE id = $1";
    return client.preparedQuery(sql)
        .execute(Tuple.of(id))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext() ? Optional.of(fromRow(iterator.next())) : Optional.empty();
        });
  }

  /** Select the next batch of global records ordered by id after the optional checkpoint. */
  public Future<List<GlobalRecord>> selectBatchAfter(SqlClient client, UUID checkpoint,
      int batchSize) {
    SqlBuilder query = SqlBuilder.select(COLUMNS).from(table());
    if (checkpoint != null) {
      query.where("id > ?", checkpoint);
    }
    query.orderBy("id").limit(batchSize);
    SqlBuilder.SqlQuery built = query.build();
    return client.preparedQuery(built.sql())
        .execute(built.params())
        .map(rows -> {
          List<GlobalRecord> result = new ArrayList<>();
          rows.forEach(row -> result.add(fromRow(row)));
          return result;
        });
  }

  public SqlBuilder.SqlQuery selectAllQuery() {
    return new SqlBuilder.SqlQuery("SELECT " + COLUMNS + " FROM " + table(), Tuple.tuple());
  }

  /** Delete a single global record by its natural key. */
  public Future<Void> deleteByKey(SqlClient client, String localId, SourceId sourceId,
      int sourceVersion) {
    String sql = "DELETE FROM " + table()
        + " WHERE local_id = $1 AND source_id = $2 AND source_version = $3";
    return client.preparedQuery(sql)
        .execute(Tuple.of(localId, sourceId.toString(), sourceVersion))
        .mapEmpty();
  }

  /** Delete global records matching an optional SQL WHERE clause. */
  public Future<Void> deleteByWhere(SqlClient client, String sqlWhere) {
    String sql = sqlWhere == null
        ? "DELETE FROM " + table()
        : "DELETE FROM " + table() + " WHERE " + sqlWhere;
    return client.query(sql).execute().mapEmpty();
  }
}
