package com.indexdata.reservoir.server;

import com.indexdata.reservoir.server.db.GlobalRecordRepository;
import com.indexdata.reservoir.server.db.PoolInitJobRepository;
import com.indexdata.reservoir.server.db.PoolInitJobRepository.Claim;
import com.indexdata.reservoir.server.db.PoolInitJobRepository.CreatedJob;
import com.indexdata.reservoir.server.db.PoolInitJobRepository.LockedJob;
import com.indexdata.reservoir.server.entity.GlobalRecord;
import com.indexdata.reservoir.server.entity.GlobalRecordJson;
import com.indexdata.reservoir.server.entity.PoolConfig;
import com.indexdata.reservoir.server.entity.PoolInitJobJson;
import com.indexdata.reservoir.server.metrics.IngestMetrics;
import com.indexdata.reservoir.server.metrics.IngestMetricsNop;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.SqlConnection;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.HttpResponse;

/** Asynchronous, resumable pool initialization jobs. */
class PoolInitializationService {
  private static final Logger log = LogManager.getLogger(PoolInitializationService.class);

  // could be configurable. So far, increasing this value does not seem to improve performance
  static int batchSize = 50;

  private enum BatchResult {
    MORE,
    STOP
  }

  private static Storage writeStorage(RoutingContext ctx) {
    return new Storage(ctx.vertx(), Tenant.get(ctx), HttpMethod.POST);
  }

  Future<Void> post(RoutingContext ctx) {
    Storage storage = writeStorage(ctx);
    PoolInitJobRepository jobs = storage.poolInitJobs();
    String poolId = Util.getPathParameter(ctx, "id");
    return storage.poolConfigs().findById(storage.db().client(), poolId)
        .compose(pool -> {
          if (pool.isEmpty()) {
            HttpResponse.responseError(ctx, 404, "Pool " + poolId + " not found");
            return Future.succeededFuture();
          }
          return jobs.create(storage.db().client(), poolId)
              .compose(created -> {
                if (created.isEmpty()) {
                  HttpResponse.responseError(ctx, 409,
                      "A pool initialization job is already running for this tenant");
                  return Future.succeededFuture();
                }
                CreatedJob job = created.get();
                startClaim(ctx.vertx(), storage, job.claim());
                String location = ctx.request().absoluteURI() + "/" + job.claim().jobId();
                return HttpResponse.responseJson(ctx, 201)
                    .putHeader("Location", location)
                    .end(PoolInitJobJson.toJson(job.job()).encode());
              });
        });
  }

  Future<Void> get(RoutingContext ctx) {
    Storage storage = writeStorage(ctx);
    PoolInitJobRepository jobs = storage.poolInitJobs();
    String poolId = Util.getPathParameter(ctx, "id");
    UUID jobId = UUID.fromString(Util.getPathParameter(ctx, "jobId"));
    return jobs.claimExpired(storage.db().client(), poolId, jobId)
        .compose(claim -> {
          claim.ifPresent(value -> startClaim(ctx.vertx(), storage, value));
          return jobs.findById(storage.db().client(), poolId, jobId);
        })
        .compose(job -> {
          if (job.isEmpty()) {
            HttpResponse.responseError(ctx, 404,
                "Pool initialization job " + jobId + " not found");
            return Future.succeededFuture();
          }
          return HttpResponse.responseJson(ctx, 200)
              .end(PoolInitJobJson.toJson(job.get()).encode());
        });
  }

  Future<Void> getAll(RoutingContext ctx) {
    Storage storage = writeStorage(ctx);
    PoolInitJobRepository jobs = storage.poolInitJobs();
    String poolId = Util.getPathParameter(ctx, "id");
    return storage.poolConfigs().findById(storage.db().client(), poolId)
        .compose(pool -> {
          if (pool.isEmpty()) {
            HttpResponse.responseError(ctx, 404, "Pool " + poolId + " not found");
            return Future.succeededFuture();
          }
          return jobs.claimNextExpired(storage.db().client(), poolId)
              .compose(claim -> {
                claim.ifPresent(value -> startClaim(ctx.vertx(), storage, value));
                return jobs.findByPoolId(storage.db().client(), poolId);
              })
              .compose(found -> HttpResponse.responseJson(ctx, 200)
                  .end(PoolInitJobJson.toJsonArray(found).encode()));
        });
  }

  Future<Void> delete(RoutingContext ctx) {
    Storage storage = writeStorage(ctx);
    String poolId = Util.getPathParameter(ctx, "id");
    UUID jobId = UUID.fromString(Util.getPathParameter(ctx, "jobId"));
    return storage.poolInitJobs().cancelOrDelete(poolId, jobId)
        .compose(found -> {
          if (!found) {
            HttpResponse.responseError(ctx, 404,
                "Pool initialization job " + jobId + " not found");
            return Future.succeededFuture();
          }
          return ctx.response().setStatusCode(204).end();
        });
  }

  private void startClaim(Vertx vertx, Storage storage, Claim claim) {
    storage.initializationDiagnostics = InitializationDiagnostics.create(
        storage.getTenant(), claim.poolId(), claim.jobId().toString());
    storage.poolConfigs().findById(storage.db().client(), claim.poolId())
        .compose(pool -> pool.isEmpty()
            ? Future.failedFuture("Pool " + claim.poolId() + " not found")
            : storage.createIngestMatcher(pool.get(), vertx))
        .onSuccess(matcher -> runBatch(vertx, storage, claim, matcher, new IngestMetricsNop()))
        .onFailure(error -> failJob(storage, claim, error));
  }

  private void runBatch(Vertx vertx, Storage storage, Claim claim, IngestMatcher matcher,
      IngestMetrics ingestMetrics) {
    storage.initializationStage("batch", () -> processBatch(storage, claim, matcher, ingestMetrics))
        .onSuccess(result -> {
          if (result == BatchResult.MORE) {
            vertx.runOnContext(_ -> runBatch(vertx, storage, claim, matcher, ingestMetrics));
          } else if (storage.initializationDiagnostics != null) {
            storage.initializationDiagnostics.summary("stopped");
          }
        })
        .onFailure(error -> failJob(storage, claim, error));
  }

  private Future<BatchResult> processBatch(Storage storage, Claim claim, IngestMatcher matcher,
      IngestMetrics ingestMetrics) {
    PoolInitJobRepository jobs = storage.poolInitJobs();
    GlobalRecordRepository globalRecords = storage.globalRecords();
    return storage.initializationTransaction(connection ->
        jobs.lockRunning(connection, claim.jobId())
            .compose(locked -> {
              if (locked.isEmpty()) {
                return Future.succeededFuture(BatchResult.STOP);
              }
              LockedJob job = locked.get();
              if (!claim.token().equals(job.claimToken())) {
                return Future.succeededFuture(BatchResult.STOP);
              }
              return storage.initializationStage("fetch", () ->
                  globalRecords.selectBatchAfter(connection, job.checkpoint(), batchSize))
                  .compose(records -> processRecords(storage, connection, claim, matcher,
                      ingestMetrics, records));
            }));
  }

  private Future<BatchResult> processRecords(Storage storage, SqlConnection connection,
      Claim claim, IngestMatcher matcher, IngestMetrics ingestMetrics, List<GlobalRecord> records) {
    PoolInitJobRepository jobs = storage.poolInitJobs();
    if (records.isEmpty()) {
      return jobs.markCompleted(connection, claim).map(BatchResult.STOP);
    }
    Future<Void> future = Future.succeededFuture();
    for (GlobalRecord record : records) {
      future = future.compose(_ -> {
        JsonObject globalRecord = GlobalRecordJson.toJson(record);
        return storage.initializeRecord(connection, matcher, ingestMetrics,
            record.id(), globalRecord);
      });
    }
    UUID checkpoint = records.getLast().id();
    return future.compose(_ ->
            jobs.advanceCheckpoint(connection, claim, checkpoint, records.size()))
        .map(BatchResult.MORE);
  }

  private void failJob(Storage storage, Claim claim, Throwable error) {
    if (storage.initializationDiagnostics != null) {
      storage.initializationDiagnostics.summary("failed");
    }
    log.error("Pool initialization failed tenant={} pool={} job={}", storage.getTenant(),
        claim.poolId(), claim.jobId(), error);
    storage.poolInitJobs().markFailed(storage.db().client(), claim, error.getMessage())
        .onFailure(updateError -> log.error("Unable to record pool initialization failure job={}",
            claim.jobId(), updateError));
  }
}
