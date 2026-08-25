package com.indexdata.reservoir.server;

import com.indexdata.reservoir.server.entity.ClusterBuilder;
import com.indexdata.reservoir.server.entity.PoolConfig;
import com.indexdata.reservoir.server.metrics.IngestMetrics;
import com.indexdata.reservoir.server.metrics.IngestMetricsNop;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.HttpResponse;

/** Asynchronous, resumable pool initialization jobs. */
class PoolInitializationService {
  private static final Logger log = LogManager.getLogger(PoolInitializationService.class);
  private static final String STATUS_IDLE = "idle";
  private static final String DB_NOW = "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')";
  private static final String NEW_LEASE = DB_NOW + " + INTERVAL '5 minutes'";
  static int batchSize = 50;

  private record Claim(UUID jobId, String poolId, UUID token) { }

  private record CreatedJob(JsonObject json, Claim claim) { }

  private enum BatchResult {
    MORE,
    STOP
  }

  private static Storage writeStorage(RoutingContext ctx) {
    return new Storage(ctx.vertx(), Tenant.get(ctx), HttpMethod.POST);
  }

  Future<Void> post(RoutingContext ctx) {
    Storage storage = writeStorage(ctx);
    String poolId = Util.getPathParameter(ctx, "id");
    return storage.selectPoolConfig(poolId)
        .compose(pool -> {
          if (pool == null) {
            HttpResponse.responseError(ctx, 404, "Pool " + poolId + " not found");
            return Future.succeededFuture();
          }
          return createJob(storage, poolId)
              .compose(created -> {
                if (created == null) {
                  HttpResponse.responseError(ctx, 409,
                      "A pool initialization job is already running for this tenant");
                  return Future.succeededFuture();
                }
                startClaim(ctx.vertx(), storage, created.claim());
                String location = ctx.request().absoluteURI() + "/" + created.claim().jobId();
                return HttpResponse.responseJson(ctx, 201)
                    .putHeader("Location", location)
                    .end(created.json().encode());
              });
        });
  }

  Future<Void> get(RoutingContext ctx) {
    Storage storage = writeStorage(ctx);
    String poolId = Util.getPathParameter(ctx, "id");
    UUID jobId = UUID.fromString(Util.getPathParameter(ctx, "jobId"));
    return claimExpired(storage, poolId, jobId)
        .compose(claim -> {
          claim.ifPresent(value -> startClaim(ctx.vertx(), storage, value));
          return getJob(storage, poolId, jobId);
        })
        .compose(job -> {
          if (job == null) {
            HttpResponse.responseError(ctx, 404,
                "Pool initialization job " + jobId + " not found");
            return Future.succeededFuture();
          }
          return HttpResponse.responseJson(ctx, 200).end(job.encode());
        });
  }

  Future<Void> delete(RoutingContext ctx) {
    Storage storage = writeStorage(ctx);
    String poolId = Util.getPathParameter(ctx, "id");
    UUID jobId = UUID.fromString(Util.getPathParameter(ctx, "jobId"));
    return cancelOrDelete(storage, poolId, jobId)
        .compose(found -> {
          if (!found) {
            HttpResponse.responseError(ctx, 404,
                "Pool initialization job " + jobId + " not found");
            return Future.succeededFuture();
          }
          return ctx.response().setStatusCode(204).end();
        });
  }

  private Future<CreatedJob> createJob(Storage storage, String poolId) {
    UUID jobId = UUID.randomUUID();
    UUID token = UUID.randomUUID();
    String sql = "INSERT INTO " + storage.poolInitializationJobTable
        + " (id, pool_id, status, claim_token, lease_until, started_at)"
        + " VALUES ($1, $2, 'running', $3, " + NEW_LEASE + ", " + DB_NOW + ")"
        + " ON CONFLICT DO NOTHING RETURNING *";
    return storage.pool.preparedQuery(sql)
        .execute(Tuple.of(jobId, poolId, token))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          if (!iterator.hasNext()) {
            return null;
          }
          Row row = iterator.next();
          return new CreatedJob(jobToJson(row), new Claim(jobId, poolId, token));
        });
  }

  private Future<JsonObject> getJob(Storage storage, String poolId, UUID jobId) {
    String sql = "SELECT * FROM " + storage.poolInitializationJobTable
        + " WHERE id = $1 AND pool_id = $2";
    return storage.pool.preparedQuery(sql)
        .execute(Tuple.of(jobId, poolId))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext() ? jobToJson(iterator.next()) : null;
        });
  }

  private Future<Optional<Claim>> claimExpired(Storage storage, String poolId, UUID jobId) {
    UUID token = UUID.randomUUID();
    String sql = "UPDATE " + storage.poolInitializationJobTable
        + " SET claim_token = $3, lease_until = " + NEW_LEASE
        + " WHERE id = $1 AND pool_id = $2 AND status = 'running'"
        + " AND cancel_requested = FALSE"
        + " AND (lease_until IS NULL OR lease_until < " + DB_NOW + ")"
        + " RETURNING id";
    return storage.pool.preparedQuery(sql)
        .execute(Tuple.of(jobId, poolId, token))
        .map(rows -> rows.iterator().hasNext()
            ? Optional.of(new Claim(jobId, poolId, token)) : Optional.empty());
  }

  private Future<Boolean> cancelOrDelete(Storage storage, String poolId, UUID jobId) {
    return storage.pool.withTransaction(connection ->
        connection.preparedQuery("SELECT status, (lease_until IS NULL OR lease_until < "
                + DB_NOW + ") AS expired FROM "
                + storage.poolInitializationJobTable
                + " WHERE id = $1 AND pool_id = $2 FOR UPDATE")
            .execute(Tuple.of(jobId, poolId))
            .compose(rows -> {
              RowIterator<Row> iterator = rows.iterator();
              if (!iterator.hasNext()) {
                return Future.succeededFuture(false);
              }
              Row row = iterator.next();
              boolean expired = Boolean.TRUE.equals(row.getBoolean("expired"));
              if (STATUS_IDLE.equals(row.getString("status")) || expired) {
                return connection.preparedQuery("DELETE FROM " + storage.poolInitializationJobTable
                        + " WHERE id = $1")
                    .execute(Tuple.of(jobId))
                    .map(true);
              }
              return connection.preparedQuery("UPDATE " + storage.poolInitializationJobTable
                      + " SET cancel_requested = TRUE WHERE id = $1")
                  .execute(Tuple.of(jobId))
                  .map(true);
            }));
  }

  private void startClaim(Vertx vertx, Storage storage, Claim claim) {
    storage.selectPoolConfig(claim.poolId())
        .compose(pool -> pool == null
            ? Future.failedFuture("Pool " + claim.poolId() + " not found")
            : storage.createIngestMatcher(new PoolConfig(pool), vertx))
        .onSuccess(matcher -> runBatch(vertx, storage, claim, matcher, new IngestMetricsNop()))
        .onFailure(error -> failJob(storage, claim, error));
  }

  private void runBatch(Vertx vertx, Storage storage, Claim claim, IngestMatcher matcher,
      IngestMetrics ingestMetrics) {
    processBatch(storage, claim, matcher, ingestMetrics)
        .onSuccess(result -> {
          if (result == BatchResult.MORE) {
            vertx.runOnContext(ignored -> runBatch(vertx, storage, claim, matcher, ingestMetrics));
          }
        })
        .onFailure(error -> failJob(storage, claim, error));
  }

  private Future<BatchResult> processBatch(Storage storage, Claim claim, IngestMatcher matcher,
      IngestMetrics ingestMetrics) {
    return storage.pool.withTransaction(connection ->
        connection.preparedQuery("SELECT claim_token, cancel_requested, checkpoint FROM "
                + storage.poolInitializationJobTable + " WHERE id = $1 AND status = 'running'"
                + " FOR UPDATE")
            .execute(Tuple.of(claim.jobId()))
            .compose(jobRows -> {
              RowIterator<Row> iterator = jobRows.iterator();
              if (!iterator.hasNext()) {
                return Future.succeededFuture(BatchResult.STOP);
              }
              Row job = iterator.next();
              if (!claim.token().equals(job.getUUID("claim_token"))) {
                return Future.succeededFuture(BatchResult.STOP);
              }
              if (Boolean.TRUE.equals(job.getBoolean("cancel_requested"))) {
                return connection.preparedQuery("DELETE FROM " + storage.poolInitializationJobTable
                        + " WHERE id = $1")
                    .execute(Tuple.of(claim.jobId()))
                    .map(BatchResult.STOP);
              }
              return selectBatch(storage, connection, job.getUUID("checkpoint"))
                  .compose(records -> processRecords(storage, connection, claim, matcher,
                      ingestMetrics, records));
            }));
  }

  private Future<BatchResult> processRecords(Storage storage, SqlConnection connection,
      Claim claim, IngestMatcher matcher, IngestMetrics ingestMetrics, List<Row> records) {
    if (records.isEmpty()) {
      String sql = "UPDATE " + storage.poolInitializationJobTable
          + " SET status = 'idle', completed_at = " + DB_NOW
          + ", claim_token = NULL, lease_until = NULL"
          + " WHERE id = $1 AND claim_token = $2";
      return connection.preparedQuery(sql)
          .execute(Tuple.of(claim.jobId(), claim.token()))
          .map(BatchResult.STOP);
    }
    Future<Void> future = Future.succeededFuture();
    for (Row record : records) {
      future = future.compose(ignored -> {
        UUID globalId = record.getUUID("id");
        JsonObject globalRecord = ClusterBuilder.encodeRecord(record);
        return storage.runMatcher(matcher, ingestMetrics, globalRecord)
            .compose(result -> storage.updateClusterForRecord(connection, globalId, result));
      });
    }
    UUID checkpoint = records.get(records.size() - 1).getUUID("id");
    String sql = "UPDATE " + storage.poolInitializationJobTable
        + " SET checkpoint = $3, total_records = total_records + $4,"
        + " lease_until = " + NEW_LEASE
        + " WHERE id = $1 AND claim_token = $2";
    return future.compose(ignored -> connection.preparedQuery(sql)
            .execute(Tuple.of(claim.jobId(), claim.token(), checkpoint, records.size())))
        .map(BatchResult.MORE);
  }

  private Future<List<Row>> selectBatch(Storage storage, SqlConnection connection,
      UUID checkpoint) {
    String sql = "SELECT * FROM " + storage.globalRecordTable;
    Tuple parameters;
    if (checkpoint == null) {
      sql += " ORDER BY id LIMIT $1";
      parameters = Tuple.of(batchSize);
    } else {
      sql += " WHERE id > $1 ORDER BY id LIMIT $2";
      parameters = Tuple.of(checkpoint, batchSize);
    }
    return connection.preparedQuery(sql)
        .execute(parameters)
        .map(rows -> {
          List<Row> result = new ArrayList<>();
          rows.forEach(result::add);
          return result;
        });
  }

  private void failJob(Storage storage, Claim claim, Throwable error) {
    log.error("Pool initialization failed tenant={} pool={} job={}", storage.getTenant(),
        claim.poolId(), claim.jobId(), error);
    String sql = "UPDATE " + storage.poolInitializationJobTable
        + " SET status = 'idle', completed_at = " + DB_NOW
        + ", claim_token = NULL, lease_until = NULL, error = $3"
        + " WHERE id = $1 AND claim_token = $2";
    storage.pool.preparedQuery(sql)
        .execute(Tuple.of(claim.jobId(), claim.token(), error.getMessage()))
        .onFailure(updateError -> log.error("Unable to record pool initialization failure job={}",
            claim.jobId(), updateError));
  }

  private JsonObject jobToJson(Row row) {
    JsonObject json = new JsonObject()
        .put("id", row.getUUID("id").toString())
        .put("poolId", row.getString("pool_id"))
        .put("status", row.getString("status"))
        .put("totalRecords", row.getLong("total_records"));
    putTimestamp(json, "startedAt", row.getLocalDateTime("started_at"));
    putTimestamp(json, "completedAt", row.getLocalDateTime("completed_at"));
    if (row.getString("error") != null) {
      json.put("error", row.getString("error"));
    }
    return json;
  }

  private void putTimestamp(JsonObject json, String property, LocalDateTime value) {
    if (value != null) {
      json.put(property, value.atOffset(ZoneOffset.UTC).toString());
    }
  }
}
