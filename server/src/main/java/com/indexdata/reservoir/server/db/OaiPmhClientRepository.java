package com.indexdata.reservoir.server.db;

import com.indexdata.reservoir.server.entity.OaiPmhClient;
import com.indexdata.reservoir.server.entity.OaiPmhStatus;
import com.indexdata.reservoir.server.entity.OaiPmhStatusJson;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL repository for {@code oai_pmh_clients}, including harvest claim/lease fencing.
 */
public final class OaiPmhClientRepository {

  private static final String COLUMNS = "id, config, job, stop, owner, lease_until";
  private static final String DB_NOW = "(clock_timestamp() AT TIME ZONE 'UTC')";
  private static final String NEW_LEASE = DB_NOW + " + INTERVAL '5 minutes'";
  private static final String ACTIVE_CLAIM = """
      WHERE id = $1 AND owner = $2
        AND job->>'status' = 'running'
        AND stop IS NOT TRUE
        AND lease_until > %s""".formatted(DB_NOW);

  private final TenantDb db;

  public OaiPmhClientRepository(TenantDb db) {
    this.db = db;
  }

  private String table() {
    return db.tables().oaiPmhClients();
  }

  private static JsonObject withoutId(JsonObject config) {
    JsonObject copy = config.copy();
    copy.remove("id");
    return copy;
  }

  private static OaiPmhClient fromRow(Row row, boolean includeLease) {
    String id = row.getString("id");
    JsonObject config = row.getJsonObject("config");
    OaiPmhStatus status = OaiPmhStatusJson.fromDbJson(row.getJsonObject("job"));
    boolean stop = Boolean.TRUE.equals(row.getBoolean("stop"));
    UUID owner = row.getUUID("owner");
    boolean leased = includeLease && Boolean.TRUE.equals(row.getBoolean("leased"));
    return new OaiPmhClient(id, config, status, stop, owner, leased);
  }

  /** Insert a new OAI-PMH client configuration. */
  public Future<Void> insert(SqlClient client, String id, JsonObject config) {
    String sql = "INSERT INTO " + table() + " (id, config) VALUES ($1, $2)";
    return client.preparedQuery(sql)
        .execute(Tuple.of(id, withoutId(config)))
        .mapEmpty();
  }

  /** Find an OAI-PMH client by id. */
  public Future<Optional<OaiPmhClient>> findById(SqlClient client, String id) {
    String sql = "SELECT " + COLUMNS + " FROM " + table() + " WHERE id = $1";
    return client.preparedQuery(sql)
        .execute(Tuple.of(id))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext()
              ? Optional.of(fromRow(iterator.next(), false))
              : Optional.empty();
        });
  }

  /** Load all OAI-PMH clients for this tenant. */
  public Future<List<OaiPmhClient>> findAll(SqlClient client) {
    String sql = "SELECT " + COLUMNS + " FROM " + table();
    return client.query(sql)
        .execute()
        .map(rows -> {
          List<OaiPmhClient> clients = new ArrayList<>();
          rows.forEach(row -> clients.add(fromRow(row, false)));
          return clients;
        });
  }

  /** Lock an OAI-PMH client row for update inside a transaction. */
  public Future<Optional<OaiPmhClient>> findByIdForUpdate(SqlConnection conn, String id) {
    String sql = "SELECT " + COLUMNS + " FROM " + table() + " WHERE id = $1 FOR UPDATE";
    return conn.preparedQuery(sql)
        .execute(Tuple.of(id))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext()
              ? Optional.of(fromRow(iterator.next(), false))
              : Optional.empty();
        });
  }

  /** Lock an OAI-PMH client row for update and compute whether its lease is still active. */
  public Future<Optional<OaiPmhClient>> findByIdForUpdateWithLease(SqlConnection conn, String id) {
    String sql = "SELECT " + COLUMNS + ", lease_until > " + DB_NOW + " AS leased"
        + " FROM " + table() + " WHERE id = $1 FOR UPDATE";
    return conn.preparedQuery(sql)
        .execute(Tuple.of(id))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext()
              ? Optional.of(fromRow(iterator.next(), true))
              : Optional.empty();
        });
  }

  /** Update the stored config JSON for an OAI-PMH client. */
  public Future<Boolean> updateConfig(SqlClient client, String id, JsonObject config) {
    String sql = "UPDATE " + table() + " SET config = $2 WHERE id = $1";
    return client.preparedQuery(sql)
        .execute(Tuple.of(id, withoutId(config)))
        .map(rows -> rows.rowCount() > 0);
  }

  /** Delete an OAI-PMH client by id, returning true if it existed. */
  public Future<Boolean> delete(SqlClient client, String id) {
    String sql = "DELETE FROM " + table() + " WHERE id = $1";
    return client.preparedQuery(sql)
        .execute(Tuple.of(id))
        .map(rows -> rows.rowCount() > 0);
  }

  /** Persist a newly claimed running harvest owner and lease. */
  public Future<Void> claimStart(SqlConnection conn, String id, OaiPmhStatus status,
      UUID owner, boolean resumeOnly) {
    String sql = "UPDATE " + table()
        + " SET job = $2, owner = $3, lease_until = " + NEW_LEASE
        + (resumeOnly ? "" : ", stop = FALSE")
        + " WHERE id = $1";
    return conn.preparedQuery(sql)
        .execute(Tuple.of(id, OaiPmhStatusJson.toDbJson(status), owner))
        .mapEmpty();
  }

  /** Retire a legacy stopped job to idle and clear its lease. */
  public Future<Void> retireStopped(SqlConnection conn, String id, OaiPmhStatus status) {
    String sql = "UPDATE " + table()
        + " SET job = $2, owner = NULL, lease_until = NULL WHERE id = $1";
    return conn.preparedQuery(sql)
        .execute(Tuple.of(id, OaiPmhStatusJson.toDbJson(status)))
        .mapEmpty();
  }

  /** Stop a running harvest job, clear its owner/lease, and store its final status. */
  public Future<Void> markStopped(SqlConnection conn, String id, OaiPmhStatus status) {
    String sql = "UPDATE " + table()
        + " SET stop = TRUE, owner = NULL, lease_until = NULL, job = $2 WHERE id = $1";
    return conn.preparedQuery(sql)
        .execute(Tuple.of(id, OaiPmhStatusJson.toDbJson(status)))
        .mapEmpty();
  }

  /** Save a harvest checkpoint only while this worker still holds the active claim. */
  public Future<Boolean> saveCheckpoint(SqlClient client, String id, UUID owner,
      JsonObject config, OaiPmhStatus status, boolean finished) {
    String sql = "UPDATE " + table()
        + " SET config = $3, job = $4, lease_until = " + (finished ? "NULL" : NEW_LEASE)
        + (finished ? ", owner = NULL" : "")
        + " " + ACTIVE_CLAIM;
    return client.preparedQuery(sql)
        .execute(Tuple.of(id, owner, config, OaiPmhStatusJson.toDbJson(status)))
        .map(rows -> rows.rowCount() > 0);
  }

  /** Extend the lease of an active harvest claim. */
  public Future<Boolean> renewClaim(SqlClient client, String id, UUID owner) {
    String sql = "UPDATE " + table() + " SET lease_until = " + NEW_LEASE + " " + ACTIVE_CLAIM;
    return client.preparedQuery(sql)
        .execute(Tuple.of(id, owner))
        .map(rows -> rows.rowCount() > 0);
  }

  /**
   * Acquires a PostgreSQL {@code FOR SHARE} row lock verifying the harvest claim is still active.
   * Records share this lock until their transactions commit; stop, delete, and takeover acquire
   * an exclusive {@code FOR UPDATE} lock and wait for all fenced records.
   */
  public Future<Boolean> lockActiveClaimForShare(SqlConnection conn, String id, UUID owner) {
    String sql = "SELECT id FROM " + table() + " " + ACTIVE_CLAIM + " FOR SHARE";
    return conn.preparedQuery(sql)
        .execute(Tuple.of(id, owner))
        .map(rows -> rows.iterator().hasNext());
  }
}
