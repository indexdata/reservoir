package com.indexdata.reservoir.server;

import static com.indexdata.reservoir.util.EncodeXmlText.encodeXmlText;

import com.indexdata.reservoir.module.ModuleExecutable;
import com.indexdata.reservoir.server.db.ClusterRepository;
import com.indexdata.reservoir.server.db.SqlBuilder;
import com.indexdata.reservoir.server.entity.ClusterMeta;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowStream;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import java.time.Instant;
import java.time.LocalDateTime;
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
    HttpServerResponse response = ctx.response();
    response.setChunked(true);
    response.setStatusCode(200);
    response.putHeader("Content-Type", "text/xml");
    response.write(OAI_HEADER);
    response.write("  <responseDate>" + Instant.now() + "</responseDate>\n");
    response.write("  <request");
    String verb = Util.getQueryParameter(ctx, "verb");
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
      if (!(e instanceof OaiException oaiException)) {
        // failedFuture ends up as 400, so we return 500 for this
        // as OAI errors are "user" errors.
        HttpResponse.responseError(ctx, 500, e.getMessage());
        return Future.succeededFuture();
      }
      log.error(oaiException.getMessage(), oaiException);
      oaiHeader(ctx);
      String errorCode = oaiException.getErrorCode();
      ctx.response().write("  <error code=\"" + errorCode + "\">"
          + encodeXmlText(oaiException.getMessage()) + "</error>\n");
      oaiFooter(ctx);
      return Future.succeededFuture();
    });
  }

  static Future<Void> getCheck(RoutingContext ctx) {
    try {
      String verb = Util.getQueryParameter(ctx, "verb");
      if (verb == null) {
        throw OaiException.badVerb("missing verb");
      }
      String metadataPrefix = Util.getQueryParameter(ctx, "metadataPrefix");
      if (metadataPrefix != null && !"marcxml".equals(metadataPrefix)) {
        throw OaiException.cannotDisseminateFormat("only metadataPrefix \"marcxml\" supported");
      }
      return switch (verb) {
        case "Identify" -> identify(ctx);
        case "ListIdentifiers" -> listRecords(ctx, false);
        case "ListRecords" -> listRecords(ctx, true);
        case "GetRecord" -> getRecord(ctx);
        default -> throw OaiException.badVerb(verb);
      };
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

  private static Storage storageFor(RoutingContext ctx) {
    return new Storage(ctx.vertx(), Tenant.get(ctx), ctx.request().method());
  }

  static Future<Void> listRecords(RoutingContext ctx, boolean withMetadata) {
    String coded = Util.getQueryParameter(ctx, "resumptionToken");
    ResumptionToken token = coded != null ? new ResumptionToken(coded) : null;
    String set = token != null
        ? token.getSet() : Util.getQueryParameter(ctx, "set");
    String from = Util.getQueryParameter(ctx, "from");
    String until = token != null
        ? token.getUntil() : Util.getQueryParameter(ctx, "until");
    int limit = Integer.parseInt(Util.getQueryParameter(ctx, "limit", "1000"));
    Storage storage = storageFor(ctx);
    return storage.poolConfigs().findById(storage.db().client(), set).compose(optConf -> {
      if (optConf.isEmpty()) {
        throw OaiException.badArgument("set \"" + set + "\" not found");
      }
      String poolId = optConf.get().id();
      LocalDateTime parsedFrom = token == null && from != null ? Util.parseFrom(from) : null;
      LocalDateTime parsedUntil = until != null ? Util.parseUntil(until) : null;
      SqlBuilder.SqlQuery query = storage.clusters().buildOaiListQuery(
          poolId, token, parsedFrom, parsedUntil);
      ResumptionToken resumptionToken = new ResumptionToken(poolId, until);
      return storage.getTransformerOai(ctx.vertx())
          .compose(transformer -> storage.db().pool().getConnection()
              .compose(conn ->
                  listRecordsResponse(ctx, transformer, storage, conn, query,
                      limit, withMetadata, resumptionToken)
                      .onFailure(_ -> conn.close())));
    });
  }

  private static void endListResponse(RoutingContext ctx, SqlConnection conn, Transaction tx,
      String elem) {
    tx.commit().compose(_ -> conn.close());
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

  static Future<Buffer> getClusterRecordMetadata(ClusterMeta meta, ModuleExecutable transformer,
      Storage storage, SqlConnection connection, boolean withMetadata, Vertx vertx) {
    return storage.clusters()
        .findForDissemination(connection, meta.clusterId(), withMetadata)
        .compose(clusterOpt -> ClusterMarcXml.getClusterMarcXml(
            clusterOpt.orElse(null), transformer, vertx))
        .map(metadata -> {
          String begin = withMetadata ? "    <record>\n" : "";
          String end = withMetadata ? "    </record>\n" : "";
          return Buffer.buffer(
              begin
                  + "      <header" + (metadata == null
                  ? " status=\"deleted\"" : "") + ">\n"
                  + "        <identifier>"
                  + encodeXmlText(encodeOaiIdentifier(meta.clusterId())) + "</identifier>\n"
                  + "        <datestamp>"
                  + encodeXmlText(Util.formatOaiDateTime(meta.datestamp()))
                  + "</datestamp>\n"
                  + "        <setSpec>" + encodeXmlText(meta.poolId()) + "</setSpec>\n"
                  + "      </header>\n"
                  + (withMetadata && metadata != null
                  ? "    <metadata>\n" + metadata + "\n"
                  + "    </metadata>\n"
                  : "")
                  + end);
        });
  }

  @java.lang.SuppressWarnings({"squid:S107"})  // too many arguments
  static Future<Void> listRecordsResponse(RoutingContext ctx, ModuleExecutable transformer,
      Storage storage, SqlConnection conn, SqlBuilder.SqlQuery query, int limit,
      boolean withMetadata, ResumptionToken token) {

    String elem = withMetadata ? "ListRecords" : "ListIdentifiers";
    return conn.prepare(query.sql()).compose(pq ->
        conn.begin().compose(tx -> {
          HttpServerResponse response = ctx.response();
          ClusterRecordStream clusterRecordStream = new ClusterRecordStream(
              conn, response, row ->
                  getClusterRecordMetadata(ClusterRepository.metaFromRow(row),
                      transformer, storage, conn, withMetadata, ctx.vertx()));
          RowStream<Row> stream = pq.createStream(100, query.params());
          AtomicInteger cnt = new AtomicInteger();
          clusterRecordStream.drainHandler(_ -> stream.resume());
          stream.handler(row -> {
            if (cnt.get() == 0) {
              oaiHeader(ctx);
              response.write("  <" + elem + ">\n");
            }
            if (cnt.get() >= limit) {
              ClusterMeta meta = ClusterRepository.metaFromRow(row);
              token.setFrom(meta.datestamp());
              token.setId(meta.clusterId());
              stream.pause();
              clusterRecordStream.end().onComplete(_ -> {
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
          stream.endHandler(_ ->
              clusterRecordStream.end()
                  .onComplete(_ -> endListResponse(ctx, conn, tx, elem))
          );
          stream.exceptionHandler(e -> {
            log.error("stream error", e);
            endListResponse(ctx, conn, tx, elem);
          });
          return Future.succeededFuture();
        })
    );
  }

  static Future<Void> getRecord(RoutingContext ctx) {
    String identifier = Util.getQueryParameter(ctx, "identifier");
    if (identifier == null) {
      throw OaiException.badArgument("missing identifier");
    }
    UUID clusterId = decodeOaiIdentifier(identifier);
    Storage storage = storageFor(ctx);
    return storage.getTransformerOai(ctx.vertx()).compose(transformer ->
        storage.clusters().findMetaById(storage.db().client(), clusterId).compose(metaOpt -> {
          if (metaOpt.isEmpty()) {
            throw OaiException.idDoesNotExist(identifier);
          }
          ClusterMeta meta = metaOpt.get();
          return storage.db().withConnection(connection ->
              getClusterRecordMetadata(meta, transformer, storage, connection, true, ctx.vertx())
                  .map(buf -> {
                    oaiHeader(ctx);
                    HttpServerResponse response = ctx.response();
                    response.write("  <GetRecord>\n");
                    response.write(buf);
                    response.write("  </GetRecord>\n");
                    oaiFooter(ctx);
                    return null;
                  }));
        }));
  }
}
