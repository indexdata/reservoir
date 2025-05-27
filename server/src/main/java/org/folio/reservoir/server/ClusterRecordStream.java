package org.folio.reservoir.server;

import static org.folio.reservoir.server.OaiService.encodeOaiIdentifier;
import static org.folio.reservoir.server.OaiService.getClusterValues;
import static org.folio.reservoir.server.OaiService.getMetadataJava;
import static org.folio.reservoir.util.EncodeXmlText.encodeXmlText;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.WriteStream;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.util.HashSet;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.reservoir.module.ModuleExecutable;
import org.folio.reservoir.server.entity.ClusterBuilder;
import org.folio.reservoir.util.JsonToMarcXml;

public class ClusterRecordStream implements WriteStream<ClusterRecordItem> {

  private static final Logger log = LogManager.getLogger(ClusterRecordStream.class);
  boolean ended;

  Set<ClusterRecordItem> work = new HashSet<>();

  Storage storage;

  boolean withMetadata;

  Handler<Void> drainHandler;

  Handler<AsyncResult<Void>> endHandler;

  Handler<Throwable> exceptionHandler;

  ModuleExecutable transformer;

  WriteStream<Buffer> response;

  SqlConnection connection;

  int writeQueueMaxSize = 5;

  Vertx vertx;

  ClusterRecordStream(
      Vertx vertx, Storage storage, SqlConnection connection,
      WriteStream<Buffer> response, ModuleExecutable transformer, boolean withMetadata) {
    this.response = response;
    this.transformer = transformer;
    this.withMetadata = withMetadata;
    this.storage = storage;
    this.connection = connection;
    this.vertx = vertx;
  }

  @Override
  public WriteStream<ClusterRecordItem> exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  Future<ClusterBuilder> populateCluster(ClusterRecordItem cr) {
    String q = "SELECT * FROM " + storage.getGlobalRecordTable()
        + " LEFT JOIN " + storage.getClusterRecordTable() + " ON record_id = id "
        + " WHERE cluster_id = $1";
    return connection.preparedQuery(q)
        .execute(Tuple.of(cr.clusterId))
        .compose(rowSet -> {
          if (rowSet.size() == 0) {
            return Future.succeededFuture(null); // deleted record
          }
          ClusterBuilder cb = new ClusterBuilder(cr.clusterId).records(rowSet);
          if (!withMetadata) {
            return Future.succeededFuture(cb);
          }
          return getClusterValues(storage, connection, cr.clusterId, cb);
        });
  }

  Future<Buffer> getClusterRecordMetadata(ClusterRecordItem cr) {
    return populateCluster(cr)
        .compose(cb -> {
          Future<String> future;
          if (cb == null) {
            future = Future.succeededFuture(null); // deleted record
          } else if (transformer == null) {
            future = Future.succeededFuture(getMetadataJava(cb.build()));
          } else {
            JsonObject cluster = cb.build();
            future = transformer.execute(cluster).map(JsonToMarcXml::convert);
          }
          return future.map(metadata -> {
            String begin = withMetadata ? "    <record>\n" : "";
            String end = withMetadata ? "    </record>\n" : "";
            return Buffer.buffer(
                begin
                    + "      <header" + (metadata == null
                    ? " status=\"deleted\"" : "") + ">\n"
                    + "        <identifier>"
                    + encodeXmlText(encodeOaiIdentifier(cr.clusterId)) + "</identifier>\n"
                    + "        <datestamp>"
                    + encodeXmlText(Util.formatOaiDateTime(cr.datestamp))
                    + "</datestamp>\n"
                    + "        <setSpec>" + encodeXmlText(cr.oaiSet) + "</setSpec>\n"
                    + "      </header>\n"
                    + (withMetadata && metadata != null
                    ? "    <metadata>\n" + metadata + "\n"
                    + "    </metadata>\n"
                    : "")
                    + end);
          });
        });
  }

  Future<Void> perform(ClusterRecordItem cr) {
    return getClusterRecordMetadata(cr)
        .compose(buf -> response.write(buf).mapEmpty())
        .recover(e -> {
          log.warn("Failed to produce record {} cause: {}", cr.clusterId, e.getMessage());
          log.debug(e);
          return response.write(Buffer.buffer("<!-- Failed to produce record "
              + encodeXmlText(cr.clusterId.toString()) + " cause: "
              + encodeXmlText(e.getMessage()) + " -->\n")).mapEmpty();
        })
        .mapEmpty();
  }

  @Override
  public Future<Void> write(ClusterRecordItem cr) {
    work.add(cr);
    return perform(cr).onComplete(x -> {
      work.remove(cr);
      if (work.size() == writeQueueMaxSize - 1 && !ended) {
        drainHandler.handle(null);
      }
      if (work.isEmpty() && ended) {
        this.endHandler.handle(Future.succeededFuture());
      }
    });
  }

  @Override
  public void write(ClusterRecordItem clusterRecord, Handler<AsyncResult<Void>> handler) {
    write(clusterRecord).onComplete(handler);
  }

  @Override
  public void end(Handler<AsyncResult<Void>> handler) {
    if (ended) {
      throw new IllegalStateException("already ended");
    }
    ended = true;
    this.endHandler = handler;
    if (work.isEmpty()) {
      this.endHandler.handle(Future.succeededFuture());
    }
  }

  @Override
  public WriteStream<ClusterRecordItem> setWriteQueueMaxSize(int i) {
    writeQueueMaxSize = i;
    return this;
  }

  @Override
  public boolean writeQueueFull() {
    return work.size() >= writeQueueMaxSize;
  }

  @Override
  public WriteStream<ClusterRecordItem> drainHandler(@Nullable Handler<Void> handler) {
    this.drainHandler = handler;
    return this;
  }

}
