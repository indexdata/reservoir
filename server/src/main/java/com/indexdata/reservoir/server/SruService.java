package com.indexdata.reservoir.server;

import io.vertx.core.Future;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.validation.ValidationHandler;
import org.folio.tlib.postgres.PgCqlDefinition;
import org.folio.tlib.postgres.PgCqlQuery;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldAlwaysMatches;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldUuid;

public class SruService {

  private SruService() { }

  private static Future<Void> getRecords(RoutingContext ctx, Storage storage, PgCqlQuery pgCqlQuery,
      int startRecord, int maximumRecords) {

    return storage.getMarcxmlRecords(ctx, pgCqlQuery, startRecord - 1, maximumRecords, marcxml -> {
      if (marcxml == null) {
        return Future.succeededFuture();
      }
      HttpServerResponse response = ctx.response();
      response.write("    <record>\n");
      response.write("      <recordData>\n");
      response.write(marcxml);
      response.write("      </recordData>\n");
      response.write("    </record>\n");
      return Future.succeededFuture();
    });
  }

  static void  returnDiagnostics(HttpServerResponse response, String no,
      String message, String details) {
    response.write("  <diagnostics>\n");
    response.write("    <diagnostic xmlns:diag=\"http://docs.oasis-open.org/ns/search-ws/diagnostic\">\n");
    response.write("      <uri>info:srw/diagnostic/1/" + no + "</uri>\n");
    response.write("      <message>" + message + "</message>\n");
    response.write("      <details>" + details + "</details>\n");
    response.write("    </diagnostic>\n");
    response.write("  </diagnostics>\n");
  }

  static boolean checkVersion(HttpServerResponse response, RoutingContext ctx) {
    final String sruVersion = Util.getQueryParameter(ctx, "version");
    if (sruVersion != null && !sruVersion.equals("2.0")) {
      returnDiagnostics(response, "5", "Unsupported version", "2.0");
      return false;
    }
    return true;
  }

  static Future<Void> getSearchRetrieveResponse(RoutingContext ctx, String query) {
    HttpServerResponse response = ctx.response();
    if (!checkVersion(response, ctx)) {
      return Future.succeededFuture();
    }

    // no need to check for null as default value is given in API spec
    final int startRecord = Integer.parseInt(
        Util.getQueryParameter(ctx, "startRecord", "1"));
    final int maximumRecords = Integer.parseInt(
        Util.getQueryParameter(ctx, "maximumRecords", "10"));

    // should use createDefinitionBase
    PgCqlDefinition definition = PgCqlDefinition.create();
    definition.addField(CqlFields.CQL_ALL_RECORDS.getCqlName(), new PgCqlFieldAlwaysMatches());
    // id instead of clusterId in CqlFields.CLUSTER_ID
    definition.addField("rec.id",
      new PgCqlFieldUuid().withColumn(CqlFields.CLUSTER_ID.getSqllName()));
    PgCqlQuery pgCqlQuery;
    try {
      pgCqlQuery = definition.parse(query);
    } catch (Exception e) {
      returnDiagnostics(response, "10", "Query syntax error", e.getMessage());
      return Future.succeededFuture();
    }
    String recordSchema = Util.getQueryParameter(ctx, "recordSchema");
    if (recordSchema != null && !recordSchema.equals("marcxml")) {
      returnDiagnostics(response, "66", "Unknown schema for retrieval", recordSchema);
      return Future.succeededFuture();
    }
    Storage storage = new Storage(ctx);

    Future<Void> future = Future.succeededFuture();
    future = future.compose(x -> storage.getTotalRecords(pgCqlQuery)
        .map(totalRecords -> {
          response.write(
              "  <numberOfRecords>" + totalRecords + "</numberOfRecords>\n");
          return null;
        }));
    future = future.onComplete(x -> response.write("  <records>\n"));
    future = future.compose(x -> getRecords(ctx, storage, pgCqlQuery, startRecord, maximumRecords));
    return future.onComplete(x -> response.write("  </records>\n"));
  }

  static Future<Void> getExplainResponse(RoutingContext ctx) {
    HttpServerResponse response = ctx.response();

    response.write("<explainResponse xmlns=\"http://docs.oasis-open.org/ns/search-ws/sruResponse\">\n");
    response.write("  <version>2.0</version>\n");

    checkVersion(response, ctx);
    response.write("</explainResponse>\n");
    response.end();
    return Future.succeededFuture();
  }

  static Future<Void> get(RoutingContext ctx) {
    HttpServerResponse response = ctx.response();
    response.setChunked(true);
    response.putHeader("Content-Type", "text/xml");
    response.setStatusCode(200);

    response.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");

    final String query = Util.getQueryParameterQuery(ctx);
    if (query == null) {
      return getExplainResponse(ctx);
    }
    response.write("<searchRetrieveResponse xmlns=\"http://docs.oasis-open.org/ns/search-ws/sruResponse\">\n");
    response.write("  <version>2.0</version>\n");
    return getSearchRetrieveResponse(ctx, query).onComplete(x -> {
      response.write("</searchRetrieveResponse>\n");
      response.end();
    });
  }
}
