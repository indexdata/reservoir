package com.indexdata.reservoir.server;

import com.indexdata.reservoir.util.EncodeXmlText;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ClusterRecordStream implements WriteStream<Row> {

  private static final Logger log = LogManager.getLogger(ClusterRecordStream.class);
  boolean ended;

  Set<Row> work = new HashSet<>();

  Handler<Void> drainHandler;

  Promise<Void> endHandler;

  Handler<Throwable> exceptionHandler;

  WriteStream<Buffer> response;

  SqlConnection connection;

  int writeQueueMaxSize = 5;

  final Function<Row, Future<Buffer>> recordProcessor;

  ClusterRecordStream(
      SqlConnection connection, WriteStream<Buffer> response,
      Function<Row, Future<Buffer>> recordProcessor) {
    this.response = response;
    this.connection = connection;
    this.recordProcessor = recordProcessor;
    this.endHandler = Promise.promise();
  }

  @Override
  public WriteStream<Row> exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  Future<Void> perform(Row row) {
    return recordProcessor.apply(row)
    .compose(buf -> response.write(buf))
    .recover(e -> {
      log.warn("Failed to produce record {} cause: {}", row.deepToString(), e.getMessage());
      log.debug(e);
      return response.write(Buffer.buffer("<!-- Failed to produce record "
          + EncodeXmlText.encodeXmlText(row.deepToString()) + " cause: "
          + EncodeXmlText.encodeXmlText(e.getMessage()) + " -->\n")).mapEmpty();
    });
  }

  @Override
  public Future<Void> write(Row row) {
    work.add(row);
    return perform(row).onComplete(x -> {
      work.remove(row);
      if (work.size() == writeQueueMaxSize - 1 && !ended) {
        drainHandler.handle(null);
      }
      if (work.isEmpty() && ended) {
        endHandler.complete();
      }
    });
  }

  @Override
  public WriteStream<Row> setWriteQueueMaxSize(int i) {
    writeQueueMaxSize = i;
    return this;
  }

  @Override
  public boolean writeQueueFull() {
    return work.size() >= writeQueueMaxSize;
  }

  @Override
  public WriteStream<Row> drainHandler(Handler<Void> handler) {
    this.drainHandler = handler;
    return this;
  }

  @Override
  public Future<Void> end() {
    if (ended) {
      throw new IllegalStateException("already ended");
    }
    ended = true;
    if (work.isEmpty()) {
      endHandler.complete();
    }
    return endHandler.future();
  }
}
