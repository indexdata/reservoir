package com.indexdata.reservoir.server;

import com.indexdata.reservoir.server.entity.MatchKeyConfig;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.folio.tlib.postgres.PgCqlDefinition;
import org.folio.tlib.postgres.PgCqlException;
import org.folio.tlib.postgres.PgCqlFieldType;
import org.folio.tlib.postgres.PgCqlQuery;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldAlwaysMatches;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldText;
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

  static Future<Void> addMatchValueField(PgCqlDefinition definition, Storage storage,
      RoutingContext ctx, String matcherProp, String key, MatchKeyConfig matchConfig) {
    return storage.getTransformer(ctx, matcherProp)
      .map(module -> {
        PgCqlFieldType field = new PgCqlFieldText()
            .withExact()
            .withColumn(CqlFields.MATCH_VALUE.getQualifiedSqlName());
        definition.addField(key, new CqlFieldTermMapper(field, term -> {
          var terms = module.executeAsCollectionSync(
            new JsonObject()
              .put("term", term)
              .put("field", key)
          );
          if (terms.size() != 1) {
            throw new IllegalArgumentException("Module " + matcherProp
              + " must return exactly one term for input " + term
              + " got " + terms.size());
          }
          return terms.iterator().next();
        }, sql ->
            "(" + sql + " AND " + Storage.CLUSTER_VALUES_TABLE + ".match_key_config_id = '"
            + matchConfig.getId() + "')"
        ));
        return null;
      });
  }

  static Future<PgCqlDefinition> createDefinition(RoutingContext ctx, Storage storage) {
    return storage.getAvailableMatchConfigs()
      .compose(matchConfigs -> {
        PgCqlDefinition definition = PgCqlDefinition.create();
        definition.addField(CqlFields.CQL_ALL_RECORDS.getCqlName(), new PgCqlFieldAlwaysMatches());
        // id instead of clusterId in CqlFields.CLUSTER_ID
        definition.addField("rec.id",
          new PgCqlFieldUuid().withColumn(
            Storage.CLUSTER_META_TABLE + "." + CqlFields.CLUSTER_ID.getSqlName()
          ));
        Future<Void> future = Future.succeededFuture();
        for (MatchKeyConfig matchConfig : matchConfigs) {
          JsonObject cql = matchConfig.getCql();
          if (cql == null) {
            continue;
          }
          for (String key : cql.fieldNames()) {
            String matcherProp = cql.getString(key);
            if (matcherProp == null) {
              continue;
            }
            future = future.compose(x ->
              addMatchValueField(definition, storage, ctx, matcherProp, key, matchConfig)
            );
          }
        }
        return future.map(x -> definition);
      });
  }

  static Future<PgCqlQuery> createQuery(RoutingContext ctx, Storage storage, String query) {
    // could be expensive as JS module may be called
    return createDefinition(ctx, storage).map(definition -> definition.parse(query));
  }

  static Future<Void> getSearchRetrieveResponse(RoutingContext ctx, String query) {
    HttpServerResponse response = ctx.response();
    if (!checkVersion(response, ctx)) {
      return Future.succeededFuture();
    }

    int startRecord;
    int maximumRecords;
    try {
      startRecord = Integer.parseInt(Util.getQueryParameter(ctx, "startRecord", "1"));
      maximumRecords = Integer.parseInt(Util.getQueryParameter(ctx, "maximumRecords", "10"));
    } catch (NumberFormatException e) {
      returnDiagnostics(response, "6", "Unsupported parameter value", e.getMessage());
      return Future.succeededFuture();
    }
    String recordSchema = Util.getQueryParameter(ctx, "recordSchema");
    if (recordSchema != null && !recordSchema.equals("marcxml")) {
      returnDiagnostics(response, "66", "Unknown schema for retrieval", recordSchema);
      return Future.succeededFuture();
    }
    Storage storage = new Storage(ctx);
    return createQuery(ctx, storage, query)
      .otherwise(e -> {
        if (e instanceof PgCqlException) {
          returnDiagnostics(response, "10", "Query syntax error", e.getMessage());
        } else {
          returnDiagnostics(response, "47", "Cannot process query", e.getMessage());
        }
        return null;
      })
      .compose(pgCqlQuery -> {
        if (pgCqlQuery == null) {
          return Future.succeededFuture();
        }
        return storage.getTotalRecords(ctx, pgCqlQuery)
            .otherwise(e -> {
              returnDiagnostics(response, "47", "Cannot process query", e.getMessage());
              return 0;
            })
            .compose(totalRecords -> {
              response.write("  <numberOfRecords>" + totalRecords + "</numberOfRecords>\n");
              response.write("  <records>\n");
              if (totalRecords > 0) {
                return getRecords(ctx, storage, pgCqlQuery, startRecord, maximumRecords);
              }
              return Future.succeededFuture();
            })
            .onComplete(x -> response.write("  </records>\n"));
      });
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
