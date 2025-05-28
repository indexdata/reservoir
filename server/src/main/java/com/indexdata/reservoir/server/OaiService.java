package com.indexdata.reservoir.server;

import static com.indexdata.reservoir.util.EncodeXmlText.encodeXmlText;

import com.indexdata.reservoir.module.ModuleExecutable;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.ext.web.validation.ValidationHandler;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowStream;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.HttpResponse;

public final class OaiService {
  private static final Logger log = LogManager.getLogger(OaiService.class);

  private OaiService() { }

  static final String OAI_HEADER = """
      <?xml version="1.0" encoding="UTF-8"?>
      <OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/"
               xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xsi:schemaLocation="http://www.openarchives.org/OAI/2.0/
               http://www.openarchives.org/OAI/2.0/OAI-PMH.xsd">
      """;

  static String encodeOaiIdentifier(UUID clusterId) {
    return "oai:" + clusterId.toString();
  }

  static UUID decodeOaiIdentifier(String identifier) {
    int off = identifier.indexOf(':');
    return UUID.fromString(identifier.substring(off + 1));
  }

  static void oaiHeader(RoutingContext ctx) {
    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    HttpServerResponse response = ctx.response();
    response.setChunked(true);
    response.setStatusCode(200);
    response.putHeader("Content-Type", "text/xml");
    response.write(OAI_HEADER);
    response.write("  <responseDate>" + Instant.now() + "</responseDate>\n");
    response.write("  <request");
    String verb = Util.getQueryParameter(params, "verb");
    if (verb != null) {
      response.write(" verb=\"" + encodeXmlText(verb) + "\"");
    }
    response.write(">" + encodeXmlText(ctx.request().absoluteURI()) + "</request>\n");
  }

  static void oaiFooter(RoutingContext ctx) {
    HttpServerResponse response = ctx.response();
    response.write("</OAI-PMH>");
    response.end();
  }

  static Future<Void> get(RoutingContext ctx) {
    return getCheck(ctx).recover(e -> {
      if (!(e instanceof OaiException)) {
        // failedFuture ends up as 400, so we return 500 for this
        // as OAI errors are "user" errors.
        HttpResponse.responseError(ctx, 500, e.getMessage());
        return Future.succeededFuture();
      }
      log.error(e.getMessage(), e);
      oaiHeader(ctx);
      String errorCode = ((OaiException) e).getErrorCode();
      ctx.response().write("  <error code=\"" + errorCode + "\">"
          + encodeXmlText(e.getMessage()) + "</error>\n");
      oaiFooter(ctx);
      return Future.succeededFuture();
    });
  }

  static Future<Void> getCheck(RoutingContext ctx) {
    try {
      RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
      String verb = Util.getQueryParameter(params, "verb");
      if (verb == null) {
        throw OaiException.badVerb("missing verb");
      }
      String metadataPrefix = Util.getQueryParameter(params, "metadataPrefix");
      if (metadataPrefix != null && !"marcxml".equals(metadataPrefix)) {
        throw OaiException.cannotDisseminateFormat("only metadataPrefix \"marcxml\" supported");
      }
      switch (verb) {
        case "Identify":
          return identify(ctx);
        case "ListIdentifiers":
          return listRecords(ctx, false);
        case "ListRecords":
          return listRecords(ctx, true);
        case "GetRecord":
          return getRecord(ctx);
        default:
          throw OaiException.badVerb(verb);
      }
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  static Future<Void> identify(RoutingContext ctx) {
    oaiHeader(ctx);
    JsonObject config = ctx.vertx().getOrCreateContext().config();
    HttpServerResponse response = ctx.response();
    response.write("  <Identify>\n");
    response.write("    <repositoryName>");
    response.write(encodeXmlText(
        config.getString("repositoryName", "repositoryName unspecified")));
    response.write("    </repositoryName>\n");
    response.write("    <baseURL>");
    response.write(encodeXmlText(
        config.getString("baseURL", "baseURL unspecified")));
    response.write("    </baseURL>\n");
    response.write("    <protocolVersion>2.0</protocolVersion>\n");
    response.write("    <adminEmail>");
    response.write(encodeXmlText(
        config.getString("adminEmail", "admin@mail.unspecified")));
    response.write("</adminEmail>\n");
    response.write("    <earliestDatestamp>2020-01-01T00:00:00Z</earliestDatestamp>\n");
    response.write("    <deletedRecord>persistent</deletedRecord>\n");
    response.write("    <granularity>YYYY-MM-DDThh:mm:ssZ</granularity>\n");
    response.write("  </Identify>\n");
    oaiFooter(ctx);
    return Future.succeededFuture();
  }

  static Future<Void> listRecords(RoutingContext ctx, boolean withMetadata) {
    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    String coded = Util.getQueryParameter(params, "resumptionToken");
    ResumptionToken token = coded != null ? new ResumptionToken(coded) : null;
    String set = token != null
        ? token.getSet() : Util.getQueryParameter(params, "set");
    String from = Util.getQueryParameter(params, "from");
    String until = token != null
        ? token.getUntil() : Util.getQueryParameter(params, "until");
    Integer limit = params.queryParameter("limit").getInteger();
    Storage storage = new Storage(ctx);
    return storage.selectMatchKeyConfig(set).compose(conf -> {
      if (conf == null) {
        throw OaiException.badArgument("set \"" + set + "\" not found");
      }
      List<Object> tupleList = new ArrayList<>();
      tupleList.add(conf.getString("id"));
      StringBuilder sqlQuery = new StringBuilder("SELECT * FROM " + storage.getClusterMetaTable()
          + " WHERE match_key_config_id = $1");
      int no = 2;
      if (token != null) {
        tupleList.add(token.getFrom()); // from resumptionToken is with fraction of seconds
        if (token.getId() == null) {
          sqlQuery.append(" AND datestamp >= $" + no);
        } else {
          tupleList.add(token.getId());
          sqlQuery.append(" AND (datestamp = $" + no + " AND cluster_id >= $" + (no + 1)
              + " OR datestamp > $" + no + ")");
          no++;
        }
        no++;
      } else if (from != null) {
        tupleList.add(Util.parseFrom(from));
        sqlQuery.append(" AND datestamp >= $" + no);
        no++;
      }
      if (until != null) {
        tupleList.add(Util.parseUntil(until));
        sqlQuery.append(" AND datestamp < $" + no);
      }
      ResumptionToken resumptionToken = new ResumptionToken(conf.getString("id"), until);
      sqlQuery.append(" ORDER BY datestamp, cluster_id");
      return storage.getTransformer(ctx)
          .compose(transformer -> storage.getPool().getConnection().compose(conn ->
              listRecordsResponse(ctx, transformer, storage, conn, sqlQuery.toString(),
                  Tuple.from(tupleList), limit, withMetadata, resumptionToken)
          ));
    });
  }

  private static void endListResponse(RoutingContext ctx, SqlConnection conn, Transaction tx,
      String elem) {
    tx.commit().compose(y -> conn.close());
    HttpServerResponse response = ctx.response();
    if (!response.headWritten()) { // no records returned is an error which is so weird.
      oaiHeader(ctx);
      ctx.response().write("  <error code=\"noRecordsMatch\"/>\n");
    } else {
      response.write("  </" + elem + ">\n");
    }
    oaiFooter(ctx);
  }

  static void writeResumptionToken(RoutingContext ctx, ResumptionToken token) {
    HttpServerResponse response = ctx.response();
    response.write("    <resumptionToken>");
    response.write(encodeXmlText(token.encode()));
    response.write("</resumptionToken>\n");
  }

  static Future<Buffer> getClusterRecordMetadata(Row row, ModuleExecutable transformer,
      Storage storage, SqlConnection connection, boolean withMetadata, Vertx vertx) {

    ClusterRecordItem cr = new ClusterRecordItem(row);
    return cr.populateCluster(storage, connection, withMetadata)
      .compose(cb -> ClusterMarcXml.getClusterMarcXml(cb, transformer, vertx)
        .map(metadata -> {
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
        })
    );
  }

  @java.lang.SuppressWarnings({"squid:S107"})  // too many arguments
  static Future<Void> listRecordsResponse(RoutingContext ctx, ModuleExecutable transformer,
      Storage storage, SqlConnection conn, String sqlQuery, Tuple tuple, Integer limit,
      boolean withMetadata, ResumptionToken token) {

    String elem = withMetadata ? "ListRecords" : "ListIdentifiers";
    return conn.prepare(sqlQuery).compose(pq ->
        conn.begin().compose(tx -> {
          HttpServerResponse response = ctx.response();
          ClusterRecordStream clusterRecordStream = new ClusterRecordStream(
              conn, response, row ->
                getClusterRecordMetadata(row, transformer, storage, conn,
                withMetadata, ctx.vertx())
          );
          RowStream<Row> stream = pq.createStream(100, tuple);
          AtomicInteger cnt = new AtomicInteger();
          clusterRecordStream.drainHandler(x -> stream.resume());
          stream.handler(row -> {
            if (cnt.get() == 0) {
              oaiHeader(ctx);
              response.write("  <" + elem + ">\n");
            }
            if (cnt.get() >= limit) {
              token.setFrom(row.getLocalDateTime("datestamp"));
              token.setId(row.getUUID("cluster_id"));
              stream.pause();
              clusterRecordStream.end().onComplete(y -> {
                writeResumptionToken(ctx, token);
                endListResponse(ctx, conn, tx, elem);
              });
              return;
            }
            cnt.incrementAndGet();
            clusterRecordStream.write(row);
            if (clusterRecordStream.writeQueueFull()) {
              stream.pause();
            }
          });
          stream.endHandler(end ->
              clusterRecordStream.end()
                  .onComplete(y -> endListResponse(ctx, conn, tx, elem))
          );
          stream.exceptionHandler(e -> {
            log.error("stream error {}", e.getMessage(), e);
            endListResponse(ctx, conn, tx, elem);
          });
          return Future.succeededFuture();
        })
    );
  }

  static Future<Void> getRecord(RoutingContext ctx) {
    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    String identifier = Util.getQueryParameter(params, "identifier");
    if (identifier == null) {
      throw OaiException.badArgument("missing identifier");
    }
    UUID clusterId = decodeOaiIdentifier(identifier);
    Storage storage = new Storage(ctx);
    return storage.getTransformer(ctx).compose(transformer -> {
      return storage.getClusterRecord(ctx, clusterId).compose(row -> {
        if (row == null) {
          throw OaiException.idDoesNotExist(identifier);
        }
        return getClusterRecordMetadata(row, transformer, storage,
            storage.getPool().getConnection().result(), true, ctx.vertx())
            .map(buf -> {
              oaiHeader(ctx);
              HttpServerResponse response = ctx.response();
              response.write("  <GetRecord>\n");
              response.write(buf);
              response.write("  </GetRecord>\n");
              oaiFooter(ctx);
              return null;
            });
      });
    });
  }
}
