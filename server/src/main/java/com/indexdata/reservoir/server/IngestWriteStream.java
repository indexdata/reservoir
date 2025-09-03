package com.indexdata.reservoir.server;

import com.indexdata.reservoir.module.impl.ModuleJsonPath;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.WriteStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class IngestWriteStream implements WriteStream<JsonObject> {
  final Vertx vertx;
  final Storage storage;
  final IngestParams params;
  final IngestStats stats;
  final String fileName;
  final String contentType;
  Handler<Throwable> exceptionHandler;
  Promise<Void> endHandler;
  boolean ended;
  Handler<Void> drainHandler;
  List<IngestMatcher> ingestMatches;
  AtomicInteger ops = new AtomicInteger();
  int queueSize = 40;
  boolean ingest;
  Throwable failure;
  private static final Logger log = LogManager.getLogger(IngestWriteStream.class);
  private static final String LOCAL_ID = "localId";

  IngestWriteStream(Vertx vertx, Storage storage, IngestParams params,
      String fileName, String contentType) {
    this.vertx = vertx;
    this.storage = storage;
    this.params = params;
    this.fileName = fileName;
    this.contentType = contentType;
    this.stats = new IngestStats(fileName);
    this.ingest = params.ingest;
    this.endHandler = Promise.promise();
  }

  @Override
  public WriteStream<JsonObject> exceptionHandler(Handler<Throwable> handler) {
    exceptionHandler = handler;
    return this;
  }

  @Override
  public Future<Void> write(JsonObject globalRecord) {
    ops.incrementAndGet();
    return lookupId(globalRecord)
        .compose(rec -> {
          log(rec);
          Future<Void> future = Future.succeededFuture();
          String localId = rec.getString(LOCAL_ID);
          if (ingest && localId != null) {
            if (ingestMatches == null) {
              future = storage.availableIngestMatchers(vertx)
                .map(x -> {
                  ingestMatches = x;
                  return null;
                });
            }
            future = future
                .compose(x -> storage
                  .ingestGlobalRecord(
                    vertx, params.sourceId, params.sourceVersion, rec, ingestMatches)
                  .onSuccess(r -> {
                    if (r == null) {
                      stats.incrementDeleted();
                    } else if (r.booleanValue()) {
                      stats.incrementInserted();
                    } else {
                      stats.incrementUpdated();
                    }
                  }))
                .mapEmpty();
          }
          return future;
        })
        .onFailure(e -> {
          if (failure == null) {
            failure = e;
          }
          if (exceptionHandler != null) {
            exceptionHandler.handle(e);
          }
          ingest = false; // we report only error, so no need to ingest further
        })
        .onComplete(x -> {
          if (ops.decrementAndGet() == queueSize / 2 && drainHandler != null) {
            drainHandler.handle(null);
          }
          if (ops.get() == 0 && ended) {
            log.info("{} {}", params.getSummary(fileName), stats);
            if (endHandler != null) {
              if (failure != null) {
                endHandler.fail(failure);
              } else {
                endHandler.complete();
              }
            }
          }
        });
  }

  @Override
  public Future<Void> end() {
    ended = true;
    if (ops.get() == 0) {
      log.info("{} {}", params.getSummary(fileName), stats);
      endHandler.complete();
    }
    return endHandler.future();
  }

  @Override
  public WriteStream<JsonObject> setWriteQueueMaxSize(int i) {
    queueSize = i;
    return this;
  }

  @Override
  public boolean writeQueueFull() {
    return ops.get() >= queueSize;
  }

  @Override
  public WriteStream<JsonObject> drainHandler(Handler<Void> handler) {
    drainHandler = handler;
    return this;
  }

  public IngestStats stats() {
    return stats;
  }

  private static Future<Collection<String>> lookupPath(Vertx vertx,
      ModuleJsonPath jsonPath, JsonObject payload) {
    return jsonPath.executeAsCollection(null, payload);
  }

  private Future<JsonObject> lookupId(JsonObject rec) {
    Future<JsonObject> fut = Future.succeededFuture(rec);
    if (params.jsonPath != null) {
      fut = fut
        .compose(r ->
          lookupPath(vertx, params.jsonPath, r.getJsonObject("payload"))
            .map(strings -> {
              Iterator<String> iterator = strings.iterator();
              if (iterator.hasNext()) {
                r.put(LOCAL_ID, iterator.next().trim());
              } else {
                r.remove(LOCAL_ID);
              }
              return r;
            }
      ));
    }
    return fut;
  }

  private void log(JsonObject rec) {
    String localId = rec.getString(LOCAL_ID);
    if (stats.incrementProcessed() < 10) {
      if (localId != null) {
        log.info("{} found ID {} at {}", params.getSummary(fileName), localId, stats.processed());
      }
    } else if (stats.processed() % 10000 == 0) {
      log.info("{} processed: {}", params.getSummary(fileName), stats.processed());
    }
    if (localId == null) {
      stats.incrementIgnored();
      log.warn("{} missing ID at {}", params.getSummary(fileName), stats.processed());
      if (stats.ignored() < 10) {
        log.warn("{}", rec::encodePrettily);
      }
    }
  }
}
