package com.indexdata.reservoir.server;

import com.indexdata.reservoir.server.entity.ClusterBuilder;
import com.indexdata.reservoir.server.entity.OaiPmhStatus;
import com.indexdata.reservoir.server.metrics.IngestMetrics;
import com.indexdata.reservoir.util.SourceId;
import com.indexdata.reservoir.util.XmlMetadataParserMarcInJson;
import com.indexdata.reservoir.util.XmlMetadataStreamParser;
import com.indexdata.reservoir.util.oai.OaiParserStream;
import com.indexdata.reservoir.util.oai.OaiRecord;
import com.indexdata.reservoir.util.readstream.XmlFixer;
import com.indexdata.reservoir.util.readstream.XmlParser;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.openapi.validation.ValidatedRequest;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.HttpResponse;

public class OaiPmhClientService {

  private static final String DB_NOW = "(clock_timestamp() AT TIME ZONE 'UTC')";
  private static final String NEW_LEASE = DB_NOW + " + INTERVAL '5 minutes'";
  private static final String ACTIVE_CLAIM = " WHERE id = $1 AND owner = $2"
      + " AND job->>'status' = 'running' AND stop IS NOT TRUE AND lease_until > " + DB_NOW;

  long heartbeatInterval = 10_000;

  static class ClaimLostException extends RuntimeException {
    ClaimLostException() {
      super("Harvest stopped or ownership lost");
    }
  }

  static class Harvest {
    final String id;
    final UUID owner;
    final OaiPmhStatus job;
    final Promise<Void> done = Promise.promise();
    Throwable cancellation;
    Runnable abort = () -> { };
    boolean renewing;

    Harvest(String id, UUID owner, OaiPmhStatus job) {
      this.id = id;
      this.owner = owner;
      this.job = job;
    }

    void cancel(Throwable error) {
      if (cancellation == null) {
        cancellation = error;
        abort.run();
      }
    }
  }

  private static final String UNIQUE_VIOLATION = "23505";

  Vertx vertx;

  HttpClient httpClient;

  private static final String RESUMPTION_TOKEN_LITERAL = "resumptionToken";

  private static final String CONFIG_LITERAL = "config";

  private static final String B_WHERE_ID1_LITERAL = " WHERE ID = $1";

  private static final String CLIENT_ID_ALL = "_all";

  private static final String OAI_ERROR_NORECORDSMATCH = "noRecordsMatch";

  private static final Logger log = LogManager.getLogger(OaiPmhClientService.class);

  /**
   * Create OAI-PMH client service.
   *
   * @param vertx vertx context
   */
  public OaiPmhClientService(Vertx vertx) {
    this.vertx = vertx;
    var opts = new HttpClientOptions()
        .setTcpKeepAlive(true)
        .setKeepAliveTimeout(45);
    this.httpClient = vertx.createHttpClient(opts);
  }

  /**
   * Create OAI-PMH client.
   *
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> post(RoutingContext ctx) {
    Storage storage = new Storage(ctx);

    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject config = validatedRequest.getBody().getJsonObject();

    String id = config.getString("id");
    if (CLIENT_ID_ALL.equals(id)) {
      HttpResponse.responseError(ctx, 400,
          "Invalid value for OAI PMH client identifier: " + id);
      return Future.succeededFuture();
    }
    config.remove("id");
    return storage.getPool().preparedQuery("INSERT INTO " + storage.getOaiPmhClientTable()
            + " (id, config)"
            + " VALUES ($1, $2)")
        .execute(Tuple.of(id, config))
        .compose(x -> HttpResponse.responseJson(ctx, 201)
            .end(config.put("id", id).encode()))
        .recover(cause -> {
          if (cause instanceof PgException pgException
              && UNIQUE_VIOLATION.equals(pgException.getSqlState())) {
            return Future.failedFuture(new HttpException(400, cause));
          }
          return Future.failedFuture(cause);
        });
  }

  static Future<Row> getOaiPmhClient(Storage storage, SqlConnection connection, String id) {
    return connection.preparedQuery("SELECT * FROM " + storage.getOaiPmhClientTable()
            + B_WHERE_ID1_LITERAL)
        .execute(Tuple.of(id))
        .map(rowSet -> {
          RowIterator<Row> iterator = rowSet.iterator();
          if (!iterator.hasNext()) {
            return null;
          }
          return iterator.next();
        });
  }

  static Future<RowSet<Row>> getOaiPmhClients(Storage storage) {
    return storage.getPool().query("SELECT * FROM " + storage.getOaiPmhClientTable())
        .execute();
  }

  static Future<JsonObject> getConfig(Storage storage, String id) {
    return storage.getPool().withConnection(connection ->
        getOaiPmhClient(storage, connection, id).map(row -> {
          if (row == null) {
            return null;
          }
          return row.getJsonObject(CONFIG_LITERAL);
        }));
  }

  static Future<OaiPmhStatus> getJob(Storage storage, String id) {
    return storage.getPool().withConnection(connection -> getJob(storage, connection, id));
  }

  static Future<OaiPmhStatus> getJob(Storage storage, SqlConnection connection, String id) {
    return getOaiPmhClient(storage, connection, id).map(row -> getJob(row, id));
  }

  static OaiPmhStatus getJob(Row row, String id) {
    if (row == null) {
      return null;
    }
    JsonObject json = row.getJsonObject("job");
    OaiPmhStatus oaiPmhStatus;
    if (json != null) {
      // earlier version of reservoir stored config in jobs (not anymore)
      json.remove(CONFIG_LITERAL);
      oaiPmhStatus = json.mapTo(OaiPmhStatus.class);
    } else {
      oaiPmhStatus = new OaiPmhStatus();
      oaiPmhStatus.setStatusIdle();
      oaiPmhStatus.setTotalRecords(0L);
      oaiPmhStatus.setTotalRequests(0);
    }
    // because these fields did not exist in earlier reservoir versions.
    if (oaiPmhStatus.getTotalDeleted() == null) {
      oaiPmhStatus.setTotalDeleted(0L);
    }
    if (oaiPmhStatus.getTotalInserted() == null) {
      oaiPmhStatus.setTotalInserted(0L);
    }
    if (oaiPmhStatus.getTotalUpdated() == null) {
      oaiPmhStatus.setTotalUpdated(0L);
    }
    JsonObject config = row.getJsonObject(CONFIG_LITERAL);
    config.put("id", id);
    oaiPmhStatus.setConfig(config);
    return oaiPmhStatus;
  }

  /**
   * Get OAI-PMH client.
   *
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> get(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    String id = Util.getPathParameter(ctx, "id");
    return getConfig(storage, id).map(config -> {
      if (config == null) {
        HttpResponse.responseError(ctx, 404, id);
        return null;
      }
      config.put("id", id);
      HttpResponse.responseJson(ctx, 200).end(config.encode());
      return null;
    });
  }

  /**
   * Get all OAI-PMH clients.
   *
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> getCollection(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    return getOaiPmhClients(storage)
        .map(rowSet -> {
          JsonArray ar = new JsonArray();
          rowSet.forEach(x -> {
            JsonObject config = x.getJsonObject(CONFIG_LITERAL);
            config.put("id", x.getValue("id"));
            ar.add(config);
          });
          JsonObject response = new JsonObject();
          response.put("items", ar);
          response.put("resultInfo", new JsonObject().put("totalRecords", ar.size()));
          HttpResponse.responseJson(ctx, 200).end(response.encode());
          return null;
        });
  }

  /**
   * Delete OAI-PMH client.
   *
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> delete(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    String id = Util.getPathParameter(ctx, "id");
    return storage.getPool().preparedQuery("DELETE FROM " + storage.getOaiPmhClientTable()
            + B_WHERE_ID1_LITERAL)
        .execute(Tuple.of(id))
        .map(rowSet -> {
          if (rowSet.rowCount() == 0) {
            HttpResponse.responseError(ctx, 404, id);
          } else {
            ctx.response().setStatusCode(204).end();
          }
          return null;
        });
  }

  /**
   * Update OAI-PMH client.
   *
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> put(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    String id = Util.getPathParameter(ctx, "id");
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject config = validatedRequest.getBody().getJsonObject();
    config.remove("id");
    return storage.getPool().withTransaction(connection ->
        connection.preparedQuery("SELECT * FROM " + storage.getOaiPmhClientTable()
                + B_WHERE_ID1_LITERAL + " FOR UPDATE")
            .execute(Tuple.of(id)).compose(rows -> {
              RowIterator<Row> iterator = rows.iterator();
              OaiPmhStatus existing = iterator.hasNext() ? getJob(iterator.next(), id) : null;
              if (existing != null && existing.isRunning()) {
                return Future.failedFuture(
                    httpError(409, "Stop the harvest before updating it"));
              }
              if (existing != null) {
                JsonObject existingConfig = existing.getConfig();
                int existingVersion =
                    existingConfig.getInteger(ClusterBuilder.SOURCE_VERSION_LABEL, 1);
                int newVersion = config.getInteger(ClusterBuilder.SOURCE_VERSION_LABEL, 1);
                String existingSourceId =
                    existingConfig.getString(ClusterBuilder.SOURCE_ID_LABEL, "");
                String newSourceId = config.getString(ClusterBuilder.SOURCE_ID_LABEL, "");
                String existingSet = existingConfig.getString("set", "");
                String newSet = config.getString("set", "");
                // increment if same source ID and version *and* the set is changed.
                if (existingVersion == newVersion && existingSourceId.equals(newSourceId)
                    && !existingSet.equals(newSet)) {
                  config.put(ClusterBuilder.SOURCE_VERSION_LABEL, existingVersion + 1);
                  config.remove("from");
                }
              }
              return connection.preparedQuery("UPDATE " + storage.getOaiPmhClientTable()
                      + " SET config = $2 WHERE id = $1").execute(Tuple.of(id, config))
                  .map(rowsUpdated -> rowsUpdated.rowCount() > 0);
            })).compose(found -> {
              if (found) {
                return ctx.response().setStatusCode(204).end();
              }
              HttpResponse.responseError(ctx, 404, id);
              return Future.succeededFuture();
            });
  }

  private static HttpException httpError(int status, String message) {
    return new HttpException(status, new VertxException(message));
  }

  /** Save a checkpoint only while this worker still holds the claim. */
  Future<Boolean> saveJob(Storage storage, Harvest harvest, boolean finished) {
    String sql = "UPDATE " + storage.getOaiPmhClientTable()
        + " SET config = $3, job = $4, lease_until = " + (finished ? "NULL" : NEW_LEASE)
        + (finished ? ", owner = NULL" : "") + ACTIVE_CLAIM;
    return storage.getPool().preparedQuery(sql)
        .execute(Tuple.of(harvest.id, harvest.owner, harvest.job.getConfig(),
            JsonObject.mapFrom(harvest.job)))
        .map(rows -> rows.rowCount() > 0);
  }

  /**
   * Start OAI PMH client job. Starting an already leased job is an idempotent no-op.
   *
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> start(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    String id = Util.getPathParameter(ctx, "id");
    Future<Void> future;
    if (CLIENT_ID_ALL.equals(id)) {
      future = getOaiPmhClients(storage).compose(rows -> {
        List<Future<Void>> futures = new LinkedList<>();
        rows.forEach(row ->
            futures.add(startJob(ctx.vertx(), storage, row.getString("id"), false)));
        return Future.all(futures).mapEmpty();
      });
    } else {
      future = startJob(ctx.vertx(), storage, id, false);
    }
    return future.compose(ignored -> ctx.response().setStatusCode(204).end());
  }

  Future<Void> startJob(Vertx vertx, Storage storage, String id, boolean resumeOnly) {
    return claimJob(storage, id, resumeOnly).onSuccess(harvest -> {
      if (harvest != null) {
        runHarvest(vertx, storage, harvest);
      }
    }).mapEmpty();
  }

  Future<Harvest> claimJob(Storage storage, String id, boolean resumeOnly) {
    return storage.getPool().withTransaction(connection ->
        connection.preparedQuery("SELECT *, lease_until > " + DB_NOW + " AS leased FROM "
                + storage.getOaiPmhClientTable() + B_WHERE_ID1_LITERAL + " FOR UPDATE")
            .execute(Tuple.of(id)).compose(rows -> {
              RowIterator<Row> iterator = rows.iterator();
              if (!iterator.hasNext()) {
                return resumeOnly ? Future.succeededFuture()
                    : Future.failedFuture(httpError(404, id));
              }
              Row row = iterator.next();
              OaiPmhStatus job = getJob(row, id);
              if (resumeOnly && job.isRunning() && Boolean.TRUE.equals(row.getBoolean("stop"))) {
                job.setStatusIdle();
                return connection.preparedQuery("UPDATE " + storage.getOaiPmhClientTable()
                        + " SET job = $2, owner = NULL, lease_until = NULL" + B_WHERE_ID1_LITERAL)
                    .execute(Tuple.of(id, JsonObject.mapFrom(job))).map((Harvest) null);
              }
              if ((job.isRunning() && Boolean.TRUE.equals(row.getBoolean("leased")))
                  || (resumeOnly && !job.isRunning())) {
                return Future.succeededFuture();
              }
              if (!job.isRunning()) {
                job.setLastTotalRecords(0L);
                job.setLastRecsPerSec(null);
                job.setLastStartedTimestampRaw(LocalDateTime.now(ZoneOffset.UTC));
              }
              job.setStatusRunning();
              job.setError(null);
              Harvest harvest = new Harvest(id, UUID.randomUUID(), job);
              return connection.preparedQuery("UPDATE " + storage.getOaiPmhClientTable()
                      + " SET job = $2, owner = $3, lease_until = " + NEW_LEASE
                      + (resumeOnly ? "" : ", stop = FALSE") + B_WHERE_ID1_LITERAL)
                  .execute(Tuple.of(id, JsonObject.mapFrom(job), harvest.owner)).map(harvest);
            }));
  }

  Future<Boolean> renewClaim(Storage storage, SqlConnection connection, Harvest harvest) {
    return connection.preparedQuery("UPDATE " + storage.getOaiPmhClientTable()
            + " SET lease_until = " + NEW_LEASE + ACTIVE_CLAIM)
        .execute(Tuple.of(harvest.id, harvest.owner)).map(rows -> rows.rowCount() > 0);
  }

  // Records share this lock until their transactions commit. Stop, delete and takeover
  // need an exclusive lock, so they wait for all fenced records. Lease renewal belongs to
  // the heartbeat; updating the lease here would serialize otherwise independent records.
  Future<Void> guardRecord(Storage storage, SqlConnection connection, Harvest harvest) {
    if (harvest.cancellation != null) {
      return Future.failedFuture(harvest.cancellation);
    }
    return connection.preparedQuery("SELECT id FROM " + storage.getOaiPmhClientTable()
            + ACTIVE_CLAIM + " FOR SHARE")
        .execute(Tuple.of(harvest.id, harvest.owner))
        .compose(rows -> rows.iterator().hasNext()
            ? Future.succeededFuture() : Future.failedFuture(new ClaimLostException()));
  }

  private void runHarvest(Vertx vertx, Storage storage, Harvest harvest) {
    long timer = vertx.setPeriodic(heartbeatInterval, ignored -> {
      if (harvest.renewing || harvest.done.future().isComplete()) {
        return;
      }
      harvest.renewing = true;
      storage.getPool().withConnection(connection -> renewClaim(storage, connection, harvest))
          .onComplete(result -> {
            harvest.renewing = false;
            if (result.failed()) {
              harvest.cancel(result.cause());
            } else if (!result.result()) {
              harvest.cancel(new ClaimLostException());
            }
          });
    });
    harvest.done.future().onComplete(ignored -> vertx.cancelTimer(timer))
        .onFailure(error -> log.error("Unable to finish harvest id={} owner={}",
            harvest.id, harvest.owner, error));
    oaiHarvestLoop(vertx, storage, harvest, 0);
  }

  /**
   * Stop OAI PMH client job. The update waits for an in-flight record transaction.
   *
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> stop(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    String id = Util.getPathParameter(ctx, "id");
    Future<Void> future;
    if (CLIENT_ID_ALL.equals(id)) {
      future = getOaiPmhClients(storage).compose(rows -> {
        List<Future<Void>> futures = new LinkedList<>();
        rows.forEach(row -> futures.add(stopJob(storage, row.getString("id"), true)));
        return Future.all(futures).mapEmpty();
      });
    } else {
      future = stopJob(storage, id, false);
    }
    return future.compose(ignored -> ctx.response().setStatusCode(204).end());
  }

  Future<Void> stopJob(Storage storage, String id, boolean all) {
    return storage.getPool().withTransaction(connection ->
        connection.preparedQuery("SELECT * FROM " + storage.getOaiPmhClientTable()
                + B_WHERE_ID1_LITERAL + " FOR UPDATE")
            .execute(Tuple.of(id)).compose(rows -> {
              RowIterator<Row> iterator = rows.iterator();
              if (!iterator.hasNext()) {
                return all ? Future.succeededFuture()
                    : Future.failedFuture(httpError(404, id));
              }
              OaiPmhStatus job = getJob(iterator.next(), id);
              if (!job.isRunning()) {
                return all ? Future.succeededFuture()
                    : Future.failedFuture(httpError(400, "not running"));
              }
              job.setStatusIdle();
              job.setLastActiveTimestampRaw(LocalDateTime.now(ZoneOffset.UTC));
              if (job.getLastStartedTimestampRaw() != null) {
                job.setLastRunningTime(Duration.between(job.getLastStartedTimestampRaw(),
                    job.getLastActiveTimestampRaw()).toMillis());
              }
              return connection.preparedQuery("UPDATE " + storage.getOaiPmhClientTable()
                      + " SET stop = TRUE, owner = NULL, lease_until = NULL, job = $2"
                      + B_WHERE_ID1_LITERAL)
                  .execute(Tuple.of(id, JsonObject.mapFrom(job))).mapEmpty();
            }));
  }

  /**
   * Get OAI PMH client status.
   *
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> status(RoutingContext ctx) {
    Storage storage = new Storage(ctx.vertx(), Tenant.get(ctx), HttpMethod.POST);
    String id = Util.getPathParameter(ctx, "id");
    Future<JsonArray> f;
    if (CLIENT_ID_ALL.equals(id)) {
      f = getOaiPmhClients(storage).compose(rows -> {
        List<Future<Void>> futures = new LinkedList<>();
        rows.forEach(row -> futures.add(startJob(ctx.vertx(), storage, row.getString("id"), true)));
        return Future.all(futures);
      }).compose(ignored -> getOaiPmhClients(storage)).map(rows -> {
        JsonArray items = new JsonArray();
        rows.forEach(row -> items.add(getJob(row, row.getString("id")).getJsonObject()));
        return items;
      });
    } else {
      f = startJob(ctx.vertx(), storage, id, true).compose(ignored -> getJob(storage, id))
          .map(job -> job == null ? null : new JsonArray().add(job.getJsonObject()));
    }
    return f
        .onSuccess(items -> {
          if (items == null) {
            HttpResponse.responseError(ctx, 404, id);
            return;
          }
          JsonObject response = new JsonObject();
          response.put("items", items);
          HttpResponse.responseJson(ctx, 200).end(response.encode());
        })
        .mapEmpty();
  }

  static MultiMap getHttpHeaders(JsonObject config) {
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    headers.add("Accept", "text/xml");
    JsonObject userHeaders = config.getJsonObject("headers");
    if (userHeaders != null) {
      userHeaders.forEach(e -> {
        if (e.getValue() instanceof String value) {
          headers.add(e.getKey(), value);
        } else {
          throw new IllegalArgumentException("headers " + e.getKey() + " value must be string");
        }
      });
    }
    return headers;
  }

  static boolean addQueryParameterFromConfig(QueryStringEncoder enc,
      JsonObject config, String key) {
    String value = config.getString(key);
    if (value == null) {
      return false;
    }
    enc.addParam(key, value);
    return true;
  }

  static void addQueryParameterFromParams(QueryStringEncoder enc, JsonObject params) {
    if (params != null) {
      params.forEach(e -> {
        if (e.getValue() instanceof String value) {
          enc.addParam(e.getKey(), value);
        } else {
          throw new IllegalArgumentException("params " + e.getKey() + " value must be string");
        }
      });
    }
  }

  Future<Boolean> ingestRecord(
      Storage storage, OaiRecord<JsonObject> oaiRecord,
      SourceId sourceId, int sourceVersion, List<IngestMatcher> ingestMatches,
      IngestMetrics ingestMetrics, Harvest harvest) {
    try {
      JsonObject globalRecord = new JsonObject();
      globalRecord.put(ClusterBuilder.LOCAL_ID_LABEL, oaiRecord.getIdentifier());
      globalRecord.put(ClusterBuilder.SOURCE_ID_LABEL, sourceId.toString());
      globalRecord.put(ClusterBuilder.SOURCE_VERSION_LABEL, sourceVersion);
      if (oaiRecord.isDeleted()) {
        globalRecord.put("delete", true);
      } else {
        JsonObject payload = new JsonObject().put("marc", oaiRecord.getMetadata());
        globalRecord.put(ClusterBuilder.PAYLOAD_LABEL, payload);
      }
      return storage.ingestGlobalRecord(vertx, sourceId, sourceVersion,
          globalRecord, ingestMatches, ingestMetrics,
          connection -> guardRecord(storage, connection, harvest));
    } catch (Exception e) {
      log.error("{}", e.getMessage(), e);
      return Future.failedFuture(e);
    }
  }

  Future<HttpClientResponse> listRecordsRequest(JsonObject config, Harvest harvest) {
    RequestOptions requestOptions = new RequestOptions();
    requestOptions.setMethod(HttpMethod.GET);
    requestOptions.setHeaders(getHttpHeaders(config));
    QueryStringEncoder enc = new QueryStringEncoder(config.getString("url"));
    enc.addParam("verb", "ListRecords");
    if (!addQueryParameterFromConfig(enc, config, RESUMPTION_TOKEN_LITERAL)) {
      addQueryParameterFromConfig(enc, config, "from");
      addQueryParameterFromConfig(enc, config, "until");
      addQueryParameterFromConfig(enc, config, "set");
      addQueryParameterFromConfig(enc, config, "metadataPrefix");
    }
    addQueryParameterFromParams(enc, config.getJsonObject("params"));
    String absoluteUri = enc.toString();
    requestOptions.setAbsoluteURI(absoluteUri);
    return httpClient.request(requestOptions).compose(request -> {
      if (harvest.cancellation != null) {
        request.reset();
        return Future.failedFuture(harvest.cancellation);
      }
      harvest.abort = () -> request.reset();
      return request.idleTimeout(60_000).send();
    });
  }

  static Future<Void> endResponse(String resumptionToken, String error, OaiPmhStatus job) {
    JsonObject config = job.getConfig();
    LocalDateTime started = job.getLastStartedTimestampRaw();
    long runningTimeMilli =
        Duration.between(started, job.getLastActiveTimestampRaw()).toMillis();
    job.setLastRunningTime(runningTimeMilli);
    job.calculateLastRecsPerSec();
    String oldResumptionToken = config.getString(RESUMPTION_TOKEN_LITERAL);
    if (resumptionToken == null || resumptionToken.equals(oldResumptionToken)) {
      moveFromDate(config);
      config.remove(RESUMPTION_TOKEN_LITERAL);
      return Future.failedFuture(error == null ? OAI_ERROR_NORECORDSMATCH : error);
    } else {
      config.put(RESUMPTION_TOKEN_LITERAL, resumptionToken);
      return Future.succeededFuture();
    }
  }

  /**
   * If the last datestamp is at least 1 DAY or 1 HOUR before "now"
   * we bump it by 1 DAY or 1 SEC respectively, to avoid re-harvesting
   * the same files next time.
   * @param config job config
   */
  static void moveFromDate(JsonObject config) {
    String from = config.getString("from");
    if (from != null &&  Util.unitsBetween(Util.getOaiNow(), from) < 0) {
      config.put("from", Util.getNextOaiDate(from));
    }
  }

  static class HttpStatusError extends VertxException {
    private final int statusCode;
    private final String retryAfter;

    HttpStatusError(int statusCode, String retryAfter, String message) {
      super(message);
      this.statusCode = statusCode;
      this.retryAfter = retryAfter;
    }

    Long checkRetryAfter() {
      if (retryAfter == null) {
        return null;
      }
      try {
        long raSec = Long.parseLong(retryAfter);
        if (raSec > 0) {
          return Math.multiplyExact(raSec, 1000);
        }
      } catch (NumberFormatException | ArithmeticException ex) {
        try {
          ZonedDateTime zdt = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME);
          long dateMs = zdt.toInstant().toEpochMilli();
          long nowMs = System.currentTimeMillis();
          if (dateMs > nowMs) {
            return dateMs - nowMs;
          }
        } catch (Exception ex2) {
          // ignore
        }
      }
      return null;
    }

    boolean checkRetryStatus() {
      return statusCode == 408 || statusCode == 429 || statusCode == 500
          || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }
  }

  private Future<Void> handleStatusError(HttpClientResponse res) {
    Promise<Void> promise = Promise.promise();
    Buffer buffer = Buffer.buffer();
    res.handler(x -> {
      // only save first bytes, so we don't fill our memory up with big response
      if (buffer.length() < 80) {
        buffer.appendBuffer(x);
      }
    });
    res.exceptionHandler(promise::tryFail);
    res.endHandler(end -> {
      String msg = buffer.length() > 80
          ? buffer.getString(0, 80) : buffer.toString();
      var e = new HttpStatusError(res.statusCode(), res.getHeader("Retry-After"),
          "Returned HTTP status " + res.statusCode() + ": " + msg);
      promise.tryFail(e);
    });
    return promise.future();
  }

  private Future<Void> listRecordsResponse(Storage storage, Harvest harvest,
      List<IngestMatcher> ingestMatches, HttpClientResponse res) {
    OaiPmhStatus job = harvest.job;
    job.incrementTotalRequests();
    if (res.statusCode() != 200) {
      return handleStatusError(res);
    }
    JsonObject config = job.getConfig();
    final JsonObject checkpoint = config.copy();
    boolean xmlFixing = config.getBoolean("xmlFixing", false);
    XmlParser xmlParser = XmlParser.newParser(xmlFixing ? new XmlFixer(res) : res);
    XmlMetadataStreamParser<JsonObject> metadataParser = new XmlMetadataParserMarcInJson();
    SourceId sourceId = new SourceId(config.getString("sourceId"));
    final IngestMetrics ingestMetrics = IngestMetrics.create().withSource(sourceId);
    Promise<Void> promise = Promise.promise();
    AtomicInteger queue = new AtomicInteger();
    AtomicBoolean ended = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Runnable drain = () -> {
      if (queue.get() == 0) {
        if (failure.get() != null) {
          promise.tryFail(failure.get());
        } else if (ended.get()) {
          promise.tryComplete();
        }
      }
    };
    Consumer<Throwable> fail = error -> {
      if (failure.compareAndSet(null, error)) {
        xmlParser.pause();
        xmlParser.handler(null);
        xmlParser.endHandler(null);
        res.request().reset();
      }
      drain.run();
    };
    harvest.abort = () -> fail.accept(harvest.cancellation);
    int sourceVersion = config.getInteger("sourceVersion", 1);
    OaiParserStream<JsonObject> oaiParserStream = new OaiParserStream<>(xmlParser, metadataParser);
    oaiParserStream.exceptionHandler(fail::accept);
    xmlParser.endHandler(end -> {
      ended.set(true);
      drain.run();
    });
    oaiParserStream.parse(oaiRecord -> {
      if (failure.get() != null) {
        return;
      }
      String datestamp = oaiRecord.getDatestamp();
      String from = config.getString("from");
      if (from == null || datestamp.compareTo(from) > 0) {
        config.put("from", datestamp);
      }
      queue.incrementAndGet();
      if (queue.get() >= 4) {
        xmlParser.pause();
      }
      ingestRecord(storage, oaiRecord, sourceId, sourceVersion,
          ingestMatches, ingestMetrics, harvest)
          .onSuccess(upd -> {
            job.setTotalRecords(job.getTotalRecords() + 1);
            job.setLastTotalRecords(job.getLastTotalRecords() + 1);
            if (upd == null) {
              job.setTotalDeleted(job.getTotalDeleted() + 1);
            } else if (upd) {
              job.setTotalInserted(job.getTotalInserted() + 1);
            } else {
              job.setTotalUpdated(job.getTotalUpdated() + 1);
            }
          })
          .onFailure(error -> fail.accept(error instanceof ClaimLostException ? error
              : new VertxException(error.getMessage() + " when parsing record "
                  + oaiRecord.getIdentifier(), error)))
          .onComplete(ignored -> {
            queue.decrementAndGet();
            if (failure.get() == null && queue.get() < 2) {
              xmlParser.resume();
            }
            drain.run();
          });
    });
    if (harvest.cancellation != null) {
      fail.accept(harvest.cancellation);
    }
    return promise.future().recover(error -> {
      // Records can be replayed after interruption; advancing a partial page can lose records.
      config.clear().mergeIn(checkpoint);
      return Future.failedFuture(error);
    }).compose(ignored -> {
      if (oaiParserStream.getError() != null) {
        config.clear().mergeIn(checkpoint);
        return Future.<Void>failedFuture(oaiParserStream.getError());
      }
      return endResponse(oaiParserStream.getResumptionToken(), null, job);
    })
        .onComplete(ignored -> harvest.abort = () -> { });
  }

  static Long checkRetryWait(Throwable e, JsonObject config) {
    final int w = config.getInteger("waitRetries", 10);
    final long ms = Math.max(1L, 1000L * w); // non-positive means immediate
    if (e instanceof HttpStatusError httpStatusError) {
      if (httpStatusError.checkRetryStatus()) {
        Long retryMs = httpStatusError.checkRetryAfter();
        if (retryMs != null) {
          return retryMs;
        }
        return ms;
      }
    }
    if (e instanceof io.vertx.core.http.HttpClosedException
        || e instanceof java.net.SocketException
        || e instanceof java.net.SocketTimeoutException
        || e instanceof java.net.UnknownHostException) {
      return ms;
    }
    return null;
  }

  void oaiHarvestLoop(Vertx vertx, Storage storage, Harvest harvest, int retries) {
    if (harvest.cancellation != null) {
      finishHarvest(storage, harvest, harvest.cancellation);
      return;
    }
    OaiPmhStatus job = harvest.job;
    job.setError(null);
    job.setLastActiveTimestampRaw(LocalDateTime.now(ZoneOffset.UTC));
    JsonObject config = job.getConfig();
    log.info("harvest loop id={} owner={} rt={} retries={}",
        harvest.id, harvest.owner, config.getString(RESUMPTION_TOKEN_LITERAL), retries);
    storage.getPool().withConnection(connection -> guardRecord(storage, connection, harvest))
        .compose(ignored -> storage.availableIngestMatchers(vertx))
        .compose(ingestMatches -> listRecordsRequest(config, harvest)
            .compose(res -> listRecordsResponse(storage, harvest, ingestMatches, res)))
        .compose(ignored -> saveJob(storage, harvest, false))
        .onSuccess(saved -> {
          if (saved) {
            vertx.runOnContext(ignored -> oaiHarvestLoop(vertx, storage, harvest, 0));
          } else {
            harvest.done.tryComplete();
          }
        })
        .onFailure(error -> {
          Long waitMs = checkRetryWait(error, config);
          if (harvest.cancellation == null && waitMs != null
              && retries < config.getInteger("numberRetries", 3)) {
            waitRetry(vertx, harvest, waitMs)
                .onSuccess(ignored -> oaiHarvestLoop(vertx, storage, harvest, retries + 1))
                .onFailure(cause -> finishHarvest(storage, harvest, cause));
          } else {
            finishHarvest(storage, harvest,
                harvest.cancellation == null ? error : harvest.cancellation);
          }
        });
  }

  private Future<Void> waitRetry(Vertx vertx, Harvest harvest, long waitMs) {
    Promise<Void> promise = Promise.promise();
    long timer = vertx.setTimer(waitMs, ignored -> promise.tryComplete());
    harvest.abort = () -> {
      vertx.cancelTimer(timer);
      promise.tryFail(harvest.cancellation);
    };
    return promise.future().onComplete(ignored -> harvest.abort = () -> { });
  }

  private void finishHarvest(Storage storage, Harvest harvest, Throwable error) {
    harvest.cancel(error);
    harvest.abort = () -> { };
    if (error instanceof ClaimLostException) {
      harvest.done.tryComplete();
      return;
    }
    harvest.job.setStatusIdle();
    if (!OAI_ERROR_NORECORDSMATCH.equals(error.getMessage())) {
      harvest.job.setError(error.getMessage());
      log.warn("Harvest failed id={} owner={}", harvest.id, harvest.owner, error);
    }
    saveJob(storage, harvest, true).<Void>mapEmpty().onComplete(harvest.done);
  }
}
