package com.indexdata.reservoir.server.db;

import com.indexdata.reservoir.server.entity.JsonCodec;
import com.indexdata.reservoir.server.entity.PoolInitJob;
import com.indexdata.reservoir.server.entity.PoolInitJobStatus;
import io.vertx.core.Future;
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
 * PostgreSQL repository for {@code pool_initialization_jobs}.
 */
public final class PoolInitJobRepository {

  private static final String COLUMNS = """
      id, pool_id, status, claim_token, lease_until, checkpoint,
      total_records, started_at, completed_at, error""";

  private static final String DB_NOW = "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')";
  // 5 minutes makes plenty of room for one batch, which should only take a fraction of a second.
  private static final String NEW_LEASE = DB_NOW + " + INTERVAL '5 minutes'";

  /** Active lease claim held by a worker for a pool initialization job. */
  public record Claim(UUID jobId, String poolId, UUID token) { }

  /** Newly created job together with its initial worker claim. */
  public record CreatedJob(PoolInitJob job, Claim claim) { }

  /** Locked claim state read inside a batch transaction. */
  public record LockedJob(UUID claimToken, UUID checkpoint) { }

  private final TenantDb db;

  public PoolInitJobRepository(TenantDb db) {
    this.db = db;
  }

  private String table() {
    return db.tables().poolInitializationJobs();
  }

  private static PoolInitJob fromRow(Row row) {
    return new PoolInitJob(
        row.getUUID("id"),
        row.getString("pool_id"),
        PoolInitJobStatus.fromValue(row.getString("status")),
        row.getUUID("claim_token"),
        JsonCodec.toUtcInstant(row.getLocalDateTime("lease_until")),
        row.getUUID("checkpoint"),
        row.getLong("total_records"),
        JsonCodec.toUtcInstant(row.getLocalDateTime("started_at")),
        JsonCodec.toUtcInstant(row.getLocalDateTime("completed_at")),
        row.getString("error"));
  }

  /** Insert a new running job if no other running job exists for this tenant. */
  public Future<Optional<CreatedJob>> create(SqlClient client, String poolId) {
    UUID jobId = UUID.randomUUID();
    UUID token = UUID.randomUUID();
    String sql = """
        INSERT INTO %s (id, pool_id, status, claim_token, lease_until, started_at)
        VALUES ($1, $2, 'running', $3, %s, %s)
        ON CONFLICT DO NOTHING
        RETURNING %s
        """.formatted(table(), NEW_LEASE, DB_NOW, COLUMNS);
    return client.preparedQuery(sql)
        .execute(Tuple.of(jobId, poolId, token))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext()
              ? Optional.of(new CreatedJob(fromRow(iterator.next()),
                  new Claim(jobId, poolId, token)))
              : Optional.empty();
        });
  }

  /** Find a pool initialization job by pool id and job id. */
  public Future<Optional<PoolInitJob>> findById(SqlClient client, String poolId, UUID jobId) {
    String sql = """
        SELECT %s FROM %s
        WHERE id = $1 AND pool_id = $2
        """.formatted(COLUMNS, table());
    return client.preparedQuery(sql)
        .execute(Tuple.of(jobId, poolId))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext() ? Optional.of(fromRow(iterator.next())) : Optional.empty();
        });
  }

  /** List all initialization jobs for a pool, newest first. */
  public Future<List<PoolInitJob>> findByPoolId(SqlClient client, String poolId) {
    String sql = """
        SELECT %s FROM %s
        WHERE pool_id = $1
        ORDER BY started_at DESC, id DESC
        """.formatted(COLUMNS, table());
    return client.preparedQuery(sql)
        .execute(Tuple.of(poolId))
        .map(rows -> {
          List<PoolInitJob> jobs = new ArrayList<>();
          rows.forEach(row -> jobs.add(fromRow(row)));
          return jobs;
        });
  }

  /** Claim a specific running job if its lease has expired. */
  public Future<Optional<Claim>> claimExpired(SqlClient client, String poolId, UUID jobId) {
    UUID token = UUID.randomUUID();
    String sql = """
        UPDATE %s
        SET claim_token = $3, lease_until = %s
        WHERE id = $1 AND pool_id = $2 AND status = 'running'
          AND (lease_until IS NULL OR lease_until < %s)
        RETURNING id
        """.formatted(table(), NEW_LEASE, DB_NOW);
    return client.preparedQuery(sql)
        .execute(Tuple.of(jobId, poolId, token))
        .map(rows -> rows.iterator().hasNext()
            ? Optional.of(new Claim(jobId, poolId, token))
            : Optional.empty());
  }

  /** Claim the oldest expired running job for a pool, skipping locked rows. */
  public Future<Optional<Claim>> claimNextExpired(SqlClient client, String poolId) {
    UUID token = UUID.randomUUID();
    String sql = """
        UPDATE %s
        SET claim_token = $2, lease_until = %s
        WHERE id = (
          SELECT id FROM %s
          WHERE pool_id = $1 AND status = 'running'
            AND (lease_until IS NULL OR lease_until < %s)
          ORDER BY started_at
          LIMIT 1
          FOR UPDATE SKIP LOCKED
        )
        RETURNING id
        """.formatted(table(), NEW_LEASE, table(), DB_NOW);
    return client.preparedQuery(sql)
        .execute(Tuple.of(poolId, token))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext()
              ? Optional.of(new Claim(iterator.next().getUUID("id"), poolId, token))
              : Optional.empty();
        });
  }

  public Future<Boolean> cancelOrDelete(String poolId, UUID jobId) {
    return db.withTransaction(conn -> cancelOrDelete(conn, poolId, jobId));
  }

  /** Lock and delete a pool initialization job inside an existing transaction. */
  public Future<Boolean> cancelOrDelete(SqlConnection conn, String poolId, UUID jobId) {
    String selectSql = "SELECT id FROM " + table() + " WHERE id = $1 AND pool_id = $2 FOR UPDATE";
    String deleteSql = "DELETE FROM " + table() + " WHERE id = $1";
    return conn.preparedQuery(selectSql)
        .execute(Tuple.of(jobId, poolId))
        .compose(rows -> {
          if (!rows.iterator().hasNext()) {
            return Future.succeededFuture(false);
          }
          return conn.preparedQuery(deleteSql)
              .execute(Tuple.of(jobId))
              .map(true);
        });
  }

  /** Lock a running job row for update and return its current claim token and checkpoint. */
  public Future<Optional<LockedJob>> lockRunning(SqlConnection conn, UUID jobId) {
    String sql = """
        SELECT claim_token, checkpoint FROM %s
        WHERE id = $1 AND status = 'running'
        FOR UPDATE
        """.formatted(table());
    return conn.preparedQuery(sql)
        .execute(Tuple.of(jobId))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          if (!iterator.hasNext()) {
            return Optional.empty();
          }
          Row row = iterator.next();
          return Optional.of(new LockedJob(row.getUUID("claim_token"), row.getUUID("checkpoint")));
        });
  }

  /** Advance the job checkpoint, increment processed record count, and renew its lease. */
  public Future<Void> advanceCheckpoint(SqlClient client, Claim claim, UUID checkpoint,
      int processedCount) {
    String sql = """
        UPDATE %s
        SET checkpoint = $3, total_records = total_records + $4, lease_until = %s
        WHERE id = $1 AND claim_token = $2
        """.formatted(table(), NEW_LEASE);
    return client.preparedQuery(sql)
        .execute(Tuple.of(claim.jobId(), claim.token(), checkpoint, processedCount))
        .mapEmpty();
  }

  /** Mark a claimed job as completed (idle) and release its lease. */
  public Future<Void> markCompleted(SqlClient client, Claim claim) {
    String sql = """
        UPDATE %s
        SET status = 'idle', completed_at = %s, claim_token = NULL, lease_until = NULL
        WHERE id = $1 AND claim_token = $2
        """.formatted(table(), DB_NOW);
    return client.preparedQuery(sql)
        .execute(Tuple.of(claim.jobId(), claim.token()))
        .mapEmpty();
  }

  /** Mark a claimed job as failed (idle) with an error message and release its lease. */
  public Future<Void> markFailed(SqlClient client, Claim claim, String errorMessage) {
    String sql = """
        UPDATE %s
        SET status = 'idle', completed_at = %s, claim_token = NULL, lease_until = NULL, error = $3
        WHERE id = $1 AND claim_token = $2
        """.formatted(table(), DB_NOW);
    return client.preparedQuery(sql)
        .execute(Tuple.of(claim.jobId(), claim.token(), errorMessage))
        .mapEmpty();
  }
}
