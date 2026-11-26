package com.indexdata.reservoir.server;

import com.indexdata.reservoir.module.ModuleInvocation;
import com.indexdata.reservoir.server.entity.ClusterMeta;
import com.indexdata.reservoir.server.entity.PoolConfig;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.folio.tlib.postgres.PgCqlDefinition;
import org.folio.tlib.postgres.PgCqlException;
import org.folio.tlib.postgres.PgCqlFieldType;
import org.folio.tlib.postgres.PgCqlQuery;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldAlwaysMatches;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldText;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldUuid;

public class SruService {

  private static final String RECORD_SCHEMA_MARCXML = "marcxml";
  private static final String NAMESPACE_SRU_DIAGNOSTIC = "http://docs.oasis-open.org/ns/search-ws/diagnostic";
  private static final String NAMESPACE_SRU_RESPONSE = "http://docs.oasis-open.org/ns/search-ws/sruResponse";

  private record PreparedSruQuery(PgCqlQuery query, AtomicBoolean usesClusterValues) { }

  private record SruSqlQuery(String whereClause, boolean joinClusterValues) { }

  private SruService() { }

  private static Future<SruSqlQuery> evaluateWhereClause(RoutingContext ctx,
      PreparedSruQuery prepared) {
    // getWhereClause can be CPU intensive (may execute JS term mappers), so run on worker thread
    return ctx.vertx().executeBlocking(() -> {
      String where = prepared.query().getWhereClause();
      return new SruSqlQuery(where, prepared.usesClusterValues().get());
    });
  }

  private static Future<Void> getRecords(RoutingContext ctx, Storage storage,
      SruSqlQuery sruSqlQuery, int startRecord, int maximumRecords) {
    return storage.getTransformerOai(ctx.vertx()).compose(transformer ->
        storage.db().withConnection(conn ->
            storage.clusters().findMetaPageBySruWhere(
                    conn, sruSqlQuery.whereClause(), sruSqlQuery.joinClusterValues(),
                    startRecord - 1, maximumRecords)
                .compose(metas -> {
                  Future<Void> future = Future.succeededFuture();
                  for (ClusterMeta meta : metas) {
                    future = future.compose(_ ->
                        storage.clusters().findForDissemination(conn, meta.clusterId(), true)
                            .compose(clusterOpt -> ClusterMarcXml.getClusterMarcXml(
                                clusterOpt.orElse(null), transformer, ctx.vertx()))
                            .compose(marcxml -> writeRecordXml(ctx.response(), marcxml)));
                  }
                  return future;
                })));
  }

  private static Future<Void> writeRecordXml(HttpServerResponse response, String marcxml) {
    if (marcxml == null) {
      return Future.succeededFuture();
    }
    response.write("    <record>\n");
    response.write("      <recordSchema>" + RECORD_SCHEMA_MARCXML + "</recordSchema>\n");
    response.write("      <recordData>\n");
    response.write(marcxml);
    response.write("      </recordData>\n");
    response.write("    </record>\n");
    return Future.succeededFuture();
  }

  static void returnDiagnostics(HttpServerResponse response, String no,
      String message, String details) {
    response.write("  <diagnostics>\n");
    response.write("    <diagnostic xmlns=\"" + NAMESPACE_SRU_DIAGNOSTIC + "\">\n");
    response.write("      <uri>info:srw/diagnostic/1/" + no + "</uri>\n");
    response.write("      <details>" + details + "</details>\n");
    response.write("      <message>" + message + "</message>\n");
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
      RoutingContext ctx, ModuleInvocation invocation, String key, PoolConfig poolConfig,
      AtomicBoolean usesClusterValues) {
    return storage.getTransformer(ctx.vertx(), invocation)
        .map(module -> {
          PgCqlFieldType field = new PgCqlFieldText()
              .withExact()
              .withColumn(CqlFields.MATCH_VALUE.getQualifiedSqlName());
          definition.addField(key, new CqlFieldTermMapper(field, term -> {
            var terms = module.executeAsCollectionSync(
                new JsonObject()
                    .put("term", term)
                    .put("field", key));
            if (terms.size() != 1) {
              throw new IllegalArgumentException("Module " + invocation.format()
                  + " must return exactly one term for input " + term
                  + " got " + terms.size());
            }
            return terms.iterator().next();
          }, sql -> {
            usesClusterValues.set(true);
            return "(" + sql + " AND " + Storage.CLUSTER_VALUES_TABLE
                + ".match_key_config_id = '" + poolConfig.id() + "')";
          }));
          return null;
        });
  }

  static Future<PgCqlDefinition> createDefinition(RoutingContext ctx, Storage storage,
      AtomicBoolean usesClusterValues) {
    return storage.getAvailablePoolConfigs()
        .compose(poolConfigs -> {
          PgCqlDefinition definition = PgCqlDefinition.create();
          definition.addField(CqlFields.CQL_ALL_RECORDS.getCqlName(),
              new PgCqlFieldAlwaysMatches());
          // id instead of clusterId in CqlFields.CLUSTER_ID
          definition.addField("rec.id",
              new PgCqlFieldUuid().withColumn(
                  Storage.CLUSTER_META_TABLE + "." + CqlFields.CLUSTER_ID.getSqlName()));
          Future<Void> future = Future.succeededFuture();
          for (PoolConfig poolConfig : poolConfigs) {
            Map<String, ModuleInvocation> cql = poolConfig.cql();
            if (cql == null) {
              continue;
            }
            for (var entry : cql.entrySet()) {
              future = future.compose(_ ->
                  addMatchValueField(definition, storage, ctx, entry.getValue(), entry.getKey(),
                      poolConfig, usesClusterValues));
            }
          }
          return future.map(_ -> definition);
        });
  }

  static Future<PreparedSruQuery> createQuery(RoutingContext ctx, Storage storage, String query) {
    AtomicBoolean usesClusterValues = new AtomicBoolean();
    // could be expensive as JS module may be called
    return createDefinition(ctx, storage, usesClusterValues)
        .map(definition -> new PreparedSruQuery(definition.parse(query), usesClusterValues));
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
    if (recordSchema != null && !recordSchema.equals(RECORD_SCHEMA_MARCXML)) {
      returnDiagnostics(response, "66", "Unknown schema for retrieval", recordSchema);
      return Future.succeededFuture();
    }
    Storage storage = new Storage(ctx.vertx(), Tenant.get(ctx), ctx.request().method());
    return createQuery(ctx, storage, query)
        .otherwise(e -> {
          if (e instanceof PgCqlException) {
            returnDiagnostics(response, "10", "Query syntax error", e.getMessage());
          } else {
            returnDiagnostics(response, "47", "Cannot process query", e.getMessage());
          }
          return null;
        })
        .compose(prepared -> {
          if (prepared == null) {
            return Future.succeededFuture();
          }
          return evaluateWhereClause(ctx, prepared)
              .compose(sruSql -> storage.clusters()
                  .countBySruWhere(storage.db().client(), sruSql.whereClause(),
                      sruSql.joinClusterValues())
                  .compose(totalRecords -> {
                    response.write("  <numberOfRecords>" + totalRecords + "</numberOfRecords>\n");
                    if (totalRecords > 0) {
                      return response.write("  <records>\n")
                          .compose(_ -> getRecords(
                              ctx, storage, sruSql, startRecord, maximumRecords))
                          .eventually(() -> response.write("  </records>\n"));
                    }
                    return Future.succeededFuture();
                  }))
              .recover(e -> {
                returnDiagnostics(response, "47", "Cannot process query", e.getMessage());
                return Future.succeededFuture();
              });
        });
  }

  static Future<Void> getExplainResponse(RoutingContext ctx) {
    HttpServerResponse response = ctx.response();

    response.write("<explainResponse xmlns=\"" + NAMESPACE_SRU_RESPONSE + "\">\n");
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
    response.write("<searchRetrieveResponse xmlns=\"" + NAMESPACE_SRU_RESPONSE + "\">\n");
    response.write("  <version>2.0</version>\n");
    return getSearchRetrieveResponse(ctx, query).onComplete(_ -> {
      response.write("</searchRetrieveResponse>\n");
      response.end();
    });
  }
}
