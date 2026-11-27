package com.indexdata.reservoir.server;

import com.indexdata.reservoir.server.db.OaiPmhClientRepository;
import com.indexdata.reservoir.server.entity.GlobalRecordJson;
import com.indexdata.reservoir.server.entity.OaiPmhClient;
import com.indexdata.reservoir.server.entity.OaiPmhJobStatus;
import com.indexdata.reservoir.server.entity.OaiPmhStatus;
import com.indexdata.reservoir.server.entity.OaiPmhStatusJson;
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
import io.vertx.sqlclient.SqlConnection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

  long heartbeatInterval = 10_000;

  static class ClaimLostException extends RuntimeException {
    ClaimLostException() {
      super("Harvest stopped or ownership lost");
    }
  }

  static class Harvest {
    final String id;
    final UUID owner;
    final JsonObject config;
    OaiPmhStatus job;
    final Promise<Void> done = Promise.promise();
    Throwable cancellation;
    Runnable abort = () -> { };
    boolean renewing;

    Harvest(String id, UUID owner, JsonObject config, OaiPmhStatus job) {
      this.id = id;
      this.owner = owner;
      this.config = config;
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
  private static final String RESUMPTION_TOKEN_LITERAL = "resumptionToken";
  private static final String CLIENT_ID_ALL = "_all";
  private static final String OAI_ERROR_NORECORDSMATCH = "noRecordsMatch";
  private static final Logger log = LogManager.getLogger(OaiPmhClientService.class);

  Vertx vertx;
  HttpClient httpClient;

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
    Storage storage = new Storage(ctx.vertx(), Tenant.get(ctx), ctx.request().method());
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject config = validatedRequest.getBody().getJsonObject();

    String id = config.getString("id");
    if (CLIENT_ID_ALL.equals(id)) {
      HttpResponse.responseError(ctx, 400,
          "Invalid value for OAI PMH client identifier: " + id);
      return Future.succeededFuture();
    }
    return storage.oaiPmhClients().insert(storage.db().client(), id, config)
        .compose(_ -> HttpResponse.responseJson(ctx, 201)
            .end(config.copy().put("id", id).encode()))
        .recover(cause -> {
          if (cause instanceof PgException pgException
              && UNIQUE_VIOLATION.equals(pgException.getSqlState())) {
            return Future.failedFuture(new HttpException(400, cause));
          }
          return Future.failedFuture(cause);
        });
  }

  /**
   * Get OAI-PMH client.
   *
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> get(RoutingContext ctx) {
    Storage storage = new Storage(ctx.vertx(), Tenant.get(ctx), ctx.request().method());
    String id = Util.getPathParameter(ctx, "id");
    return storage.oaiPmhClients().findById(storage.db().client(), id).map(client -> {
      if (client.isEmpty()) {
        HttpResponse.responseError(ctx, 404, id);
        return null;
      }
      HttpResponse.responseJson(ctx, 200).end(client.get().configWithId().encode());
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
    Storage storage = new Storage(ctx.vertx(), Tenant.get(ctx), ctx.request().method());
    return storage.oaiPmhClients().findAll(storage.db().client())
        .map(clients -> {
          JsonArray items = new JsonArray();
          for (OaiPmhClient client : clients) {
            items.add(client.configWithId());
          }
          JsonObject response = new JsonObject()
              .put("items", items)
              .put("resultInfo", new JsonObject().put("totalRecords", items.size()));
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
    Storage storage = new Storage(ctx.vertx(), Tenant.get(ctx), ctx.request().method());
    String id = Util.getPathParameter(ctx, "id");
    return storage.oaiPmhClients().delete(storage.db().client(), id)
        .map(deleted -> {
          if (!deleted) {
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
    Storage storage = new Storage(ctx.vertx(), Tenant.get(ctx), ctx.request().method());
    OaiPmhClientRepository repo = storage.oaiPmhClients();
    String id = Util.getPathParameter(ctx, "id");
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject updatedConfig = validatedRequest.getBody().getJsonObject().copy();
    updatedConfig.remove("id");

    return storage.db().withTransaction(connection ->
        repo.findByIdForUpdate(connection, id).compose(existingOpt -> {
          if (existingOpt.isEmpty()) {
            return Future.succeededFuture(false);
          }
          OaiPmhClient existing = existingOpt.get();
          if (existing.status().isRunning()) {
            return Future.failedFuture(httpError(409, "Stop the harvest before updating it"));
          }
          JsonObject existingConfig = existing.config() != null
              ? existing.config() : new JsonObject();
          int existingVersion =
              existingConfig.getInteger(GlobalRecordJson.SOURCE_VERSION, 1);
          int newVersion = updatedConfig.getInteger(GlobalRecordJson.SOURCE_VERSION, 1);
          String existingSourceId =
              existingConfig.getString(GlobalRecordJson.SOURCE_ID, "");
          String newSourceId = updatedConfig.getString(GlobalRecordJson.SOURCE_ID, "");
          String existingSet = existingConfig.getString("set", "");
          String newSet = updatedConfig.getString("set", "");
          // increment if same source ID and version *and* the set is changed.
          if (existingVersion == newVersion && existingSourceId.equals(newSourceId)
              && !existingSet.equals(newSet)) {
            updatedConfig.put(GlobalRecordJson.SOURCE_VERSION, existingVersion + 1);
            updatedConfig.remove("from");
          }
          return repo.updateConfig(connection, id, updatedConfig);
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
    return storage.oaiPmhClients().saveCheckpoint(
        storage.db().client(), harvest.id, harvest.owner, harvest.config, harvest.job, finished);
  }

  /**
   * Start OAI PMH client job. Starting an already leased job is an idempotent no-op.
   *
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> start(RoutingContext ctx) {
    Storage storage = new Storage(ctx.vertx(), Tenant.get(ctx), ctx.request().method());
    String id = Util.getPathParameter(ctx, "id");
    Future<Void> future;
    if (CLIENT_ID_ALL.equals(id)) {
      future = storage.oaiPmhClients().findAll(storage.db().client()).compose(clients -> {
        List<Future<Void>> futures = new ArrayList<>(clients.size());
        for (OaiPmhClient client : clients) {
          futures.add(startJob(ctx.vertx(), storage, client.id(), false));
        }
        return Future.all(futures).mapEmpty();
      });
    } else {
      future = startJob(ctx.vertx(), storage, id, false);
    }
    return future.compose(_ -> ctx.response().setStatusCode(204).end());
  }

  Future<Void> startJob(Vertx vertx, Storage storage, String id, boolean resumeOnly) {
    return claimJob(storage, id, resumeOnly).onSuccess(harvest -> {
      if (harvest != null) {
        runHarvest(vertx, storage, harvest);
      }
    }).mapEmpty();
  }

  Future<Harvest> claimJob(Storage storage, String id, boolean resumeOnly) {
    OaiPmhClientRepository repo = storage.oaiPmhClients();
    return storage.db().withTransaction(connection ->
        repo.findByIdForUpdateWithLease(connection, id).compose(clientOpt -> {
          if (clientOpt.isEmpty()) {
            return resumeOnly ? Future.succeededFuture()
                : Future.failedFuture(httpError(404, id));
          }
          OaiPmhClient client = clientOpt.get();
          OaiPmhStatus job = client.status();
          if (resumeOnly && job.isRunning() && client.stop()) {
            OaiPmhStatus retired = job.withStatus(OaiPmhJobStatus.IDLE);
            return repo.retireStopped(connection, id, retired).map((Harvest) null);
          }
          if ((job.isRunning() && client.leased())
              || (resumeOnly && !job.isRunning())) {
            return Future.succeededFuture();
          }
          OaiPmhStatus running = job.startingAt(LocalDateTime.now(ZoneOffset.UTC));
          JsonObject configCopy = client.config() != null
              ? client.config().copy() : new JsonObject();
          Harvest harvest = new Harvest(id, UUID.randomUUID(), configCopy, running);
          return repo.claimStart(connection, id, running, harvest.owner, resumeOnly)
              .map(harvest);
        }));
  }

  Future<Boolean> renewClaim(Storage storage, SqlConnection connection, Harvest harvest) {
    return storage.oaiPmhClients().renewClaim(connection, harvest.id, harvest.owner);
  }

  Future<Void> guardRecord(Storage storage, SqlConnection connection, Harvest harvest) {
    if (harvest.cancellation != null) {
      return Future.failedFuture(harvest.cancellation);
    }
    return storage.oaiPmhClients().lockActiveClaimForShare(connection, harvest.id, harvest.owner)
        .compose(held -> held
            ? Future.succeededFuture()
            : Future.failedFuture(new ClaimLostException()));
  }

  private void runHarvest(Vertx vertx, Storage storage, Harvest harvest) {
    long timer = vertx.setPeriodic(heartbeatInterval, _ -> {
      if (harvest.renewing || harvest.done.future().isComplete()) {
        return;
      }
      harvest.renewing = true;
      storage.db().withConnection(connection -> renewClaim(storage, connection, harvest))
          .onComplete(result -> {
            harvest.renewing = false;
            if (result.failed()) {
              harvest.cancel(result.cause());
            } else if (!result.result()) {
              harvest.cancel(new ClaimLostException());
            }
          });
    });
    harvest.done.future().onComplete(_ -> vertx.cancelTimer(timer))
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
    Storage storage = new Storage(ctx.vertx(), Tenant.get(ctx), ctx.request().method());
    String id = Util.getPathParameter(ctx, "id");
    Future<Void> future;
    if (CLIENT_ID_ALL.equals(id)) {
      future = storage.oaiPmhClients().findAll(storage.db().client()).compose(clients -> {
        List<Future<Void>> futures = new ArrayList<>(clients.size());
        for (OaiPmhClient client : clients) {
          futures.add(stopJob(storage, client.id(), true));
        }
        return Future.all(futures).mapEmpty();
      });
    } else {
      future = stopJob(storage, id, false);
    }
    return future.compose(_ -> ctx.response().setStatusCode(204).end());
  }

  Future<Void> stopJob(Storage storage, String id, boolean all) {
    OaiPmhClientRepository repo = storage.oaiPmhClients();
    return storage.db().withTransaction(connection ->
        repo.findByIdForUpdate(connection, id).compose(clientOpt -> {
          if (clientOpt.isEmpty()) {
            return all ? Future.succeededFuture()
                : Future.failedFuture(httpError(404, id));
          }
          OaiPmhStatus job = clientOpt.get().status();
          if (!job.isRunning()) {
            return all ? Future.succeededFuture()
                : Future.failedFuture(httpError(400, "not running"));
          }
          OaiPmhStatus stopped = job.stoppedAt(LocalDateTime.now(ZoneOffset.UTC));
          return repo.markStopped(connection, id, stopped);
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
    OaiPmhClientRepository repo = storage.oaiPmhClients();
    String id = Util.getPathParameter(ctx, "id");
    Future<JsonArray> f;
    if (CLIENT_ID_ALL.equals(id)) {
      f = repo.findAll(storage.db().client()).compose(clients -> {
        List<Future<Void>> futures = new ArrayList<>();
        for (OaiPmhClient client : clients) {
          if (client.status().isRunning()) {
            futures.add(startJob(ctx.vertx(), storage, client.id(), true));
          }
        }
        return Future.all(futures);
      }).compose(_ -> repo.findAll(storage.db().client())).map(clients -> {
        JsonArray items = new JsonArray();
        for (OaiPmhClient client : clients) {
          items.add(OaiPmhStatusJson.toApiJson(client.status(), client.configWithId()));
        }
        return items;
      });
    } else {
      f = startJob(ctx.vertx(), storage, id, true)
          .compose(_ -> repo.findById(storage.db().client(), id))
          .map(clientOpt -> clientOpt
              .map(client -> new JsonArray().add(
                  OaiPmhStatusJson.toApiJson(client.status(), client.configWithId())))
              .orElse(null));
    }
    return f
        .onSuccess(items -> {
          if (items == null) {
            HttpResponse.responseError(ctx, 404, id);
            return;
          }
          JsonObject response = new JsonObject().put("items", items);
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
      globalRecord.put(GlobalRecordJson.LOCAL_ID, oaiRecord.getIdentifier());
      globalRecord.put(GlobalRecordJson.SOURCE_ID, sourceId.toString());
      globalRecord.put(GlobalRecordJson.SOURCE_VERSION, sourceVersion);
      if (oaiRecord.isDeleted()) {
        globalRecord.put("delete", true);
      } else {
        JsonObject payload = new JsonObject().put("marc", oaiRecord.getMetadata());
        globalRecord.put(GlobalRecordJson.PAYLOAD, payload);
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
    requestOptions.setAbsoluteURI(enc.toString());
    return httpClient.request(requestOptions).compose(request -> {
      if (harvest.cancellation != null) {
        request.reset();
        return Future.failedFuture(harvest.cancellation);
      }
      harvest.abort = request::reset;
      return request.idleTimeout(60_000).send();
    });
  }

  static Future<Void> endResponse(String resumptionToken, String error, Harvest harvest) {
    JsonObject config = harvest.config;
    harvest.job = harvest.job.completingPage();
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
    if (from != null && Util.unitsBetween(Util.getOaiNow(), from) < 0) {
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
    res.endHandler(_ -> {
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
    harvest.job = harvest.job.incrementingRequests();
    if (res.statusCode() != 200) {
      return handleStatusError(res);
    }
    JsonObject config = harvest.config;
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
    xmlParser.endHandler(_ -> {
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
          .onSuccess(upd -> harvest.job = harvest.job.recordingIngest(upd))
          .onFailure(error -> fail.accept(error instanceof ClaimLostException ? error
              : new VertxException(error.getMessage() + " when parsing record "
                  + oaiRecord.getIdentifier(), error)))
          .onComplete(_ -> {
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
    }).compose(_ -> {
      if (oaiParserStream.getError() != null) {
        config.clear().mergeIn(checkpoint);
        return Future.<Void>failedFuture(oaiParserStream.getError());
      }
      return endResponse(oaiParserStream.getResumptionToken(), null, harvest);
    })
        .onComplete(_ -> harvest.abort = () -> { });
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
    harvest.job = harvest.job
        .withError(null)
        .withLastActiveTimestamp(LocalDateTime.now(ZoneOffset.UTC));
    JsonObject config = harvest.config;
    log.info("harvest loop id={} owner={} rt={} retries={}",
        harvest.id, harvest.owner, config.getString(RESUMPTION_TOKEN_LITERAL), retries);
    storage.db().withConnection(connection -> guardRecord(storage, connection, harvest))
        .compose(_ -> storage.availableIngestMatchers(vertx))
        .compose(ingestMatches -> listRecordsRequest(config, harvest)
            .compose(res -> listRecordsResponse(storage, harvest, ingestMatches, res)))
        .compose(_ -> saveJob(storage, harvest, false))
        .onSuccess(saved -> {
          if (saved) {
            vertx.runOnContext(_ -> oaiHarvestLoop(vertx, storage, harvest, 0));
          } else {
            harvest.done.tryComplete();
          }
        })
        .onFailure(error -> {
          Long waitMs = checkRetryWait(error, config);
          if (harvest.cancellation == null && waitMs != null
              && retries < config.getInteger("numberRetries", 3)) {
            waitRetry(vertx, harvest, waitMs)
                .onSuccess(_ -> oaiHarvestLoop(vertx, storage, harvest, retries + 1))
                .onFailure(cause -> finishHarvest(storage, harvest, cause));
          } else {
            finishHarvest(storage, harvest,
                harvest.cancellation == null ? error : harvest.cancellation);
          }
        });
  }

  private Future<Void> waitRetry(Vertx vertx, Harvest harvest, long waitMs) {
    Promise<Void> promise = Promise.promise();
    long timer = vertx.setTimer(waitMs, _ -> promise.tryComplete());
    harvest.abort = () -> {
      vertx.cancelTimer(timer);
      promise.tryFail(harvest.cancellation);
    };
    return promise.future().onComplete(_ -> harvest.abort = () -> { });
  }

  private void finishHarvest(Storage storage, Harvest harvest, Throwable error) {
    harvest.cancel(error);
    harvest.abort = () -> { };
    if (error instanceof ClaimLostException) {
      harvest.done.tryComplete();
      return;
    }
    OaiPmhStatus finished = harvest.job.withStatus(OaiPmhJobStatus.IDLE);
    if (!OAI_ERROR_NORECORDSMATCH.equals(error.getMessage())) {
      finished = finished.withError(error.getMessage());
      log.warn("Harvest failed id={} owner={}", harvest.id, harvest.owner, error);
    }
    harvest.job = finished;
    saveJob(storage, harvest, true).<Void>mapEmpty().onComplete(harvest.done);
  }
}
