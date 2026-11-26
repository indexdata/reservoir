package com.indexdata.reservoir.server.db;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowStream;
import io.vertx.sqlclient.SqlConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reusable cursor streaming helper that manages the full PostgreSQL prepared-statement,
 * transaction, backpressure, stream-close, and commit/rollback lifecycle.
 */
public final class RowStreamer {

  private static final Logger log = LogManager.getLogger(RowStreamer.class);

  private RowStreamer() { }

  /**
   * Stream rows synchronously on an existing connection inside a transaction.
   */
  public static <T> Future<T> streamSync(SqlConnection connection, SqlBuilder.SqlQuery query,
      int fetchSize, Consumer<Row> rowConsumer, Supplier<T> resultSupplier) {
    return streamAsync(connection, query, fetchSize, row -> {
      try {
        rowConsumer.accept(row);
        return Future.succeededFuture();
      } catch (Throwable t) {
        return Future.failedFuture(t);
      }
    }, () -> Future.succeededFuture(resultSupplier.get()));
  }

  /**
   * Stream rows asynchronously with pause/resume backpressure on an existing connection.
   * Ensures the stream is closed and the transaction is committed on completion or rolled back
   * on any row or stream error.
   */
  public static <T> Future<T> streamAsync(SqlConnection connection, SqlBuilder.SqlQuery query,
      int fetchSize, Function<Row, Future<Void>> rowHandler,
      Supplier<Future<T>> onBeforeCommit) {
    return connection.prepare(query.sql()).compose(pq ->
        connection.begin().compose(tx -> {
          RowStream<Row> stream = pq.createStream(fetchSize, query.params());
          Promise<T> promise = Promise.promise();
          AtomicBoolean finished = new AtomicBoolean();
          Handler<Throwable> fail = error -> {
            if (!finished.compareAndSet(false, true)) {
              return;
            }
            log.error(error.getMessage(), error);
            stream.pause();
            stream.close()
                .recover(closeError -> {
                  if (closeError != error) {
                    error.addSuppressed(closeError);
                  }
                  return Future.succeededFuture();
                })
                .compose(_ -> pq.close().recover(closeError -> {
                  if (closeError != error) {
                    error.addSuppressed(closeError);
                  }
                  return Future.succeededFuture();
                }))
                .compose(_ -> tx.rollback())
                .onComplete(rollback -> {
                  if (rollback.failed() && rollback.cause() != error) {
                    error.addSuppressed(rollback.cause());
                  }
                  promise.tryFail(error);
                });
          };
          stream.exceptionHandler(fail);
          stream.endHandler(_ -> {
            if (!finished.compareAndSet(false, true)) {
              return;
            }
            stream.close()
                .recover(_ -> Future.succeededFuture())
                .compose(_ -> pq.close().recover(_ -> Future.succeededFuture()))
                .compose(_ -> onBeforeCommit.get())
                .compose(result -> tx.commit().map(result))
                .onSuccess(promise::tryComplete)
                .onFailure(error -> tx.rollback()
                    .onComplete(_ -> promise.tryFail(error)));
          });
          stream.handler(row -> {
            stream.pause();
            Future<Void> handled;
            try {
              handled = rowHandler.apply(row);
            } catch (Throwable t) {
              fail.handle(t);
              return;
            }
            handled
                .onFailure(fail)
                .onSuccess(_ -> {
                  if (!finished.get()) {
                    stream.resume();
                  }
                });
          });
          return promise.future();
        }));
  }
}
