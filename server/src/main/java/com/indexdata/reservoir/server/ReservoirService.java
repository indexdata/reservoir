package com.indexdata.reservoir.server;

import com.indexdata.reservoir.module.ModuleCache;
import com.indexdata.reservoir.module.ModuleInvocation;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import com.indexdata.reservoir.util.readstream.LargeJsonReadStream;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.openapi.contract.OpenAPIContract;
import io.vertx.openapi.validation.RequestParameter;
import io.vertx.openapi.validation.ValidatedRequest;
import java.util.UUID;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.HttpResponse;
import org.folio.okapi.common.ModuleVersionReporter;
import org.folio.tlib.RouterCreator;
import org.folio.tlib.TenantInitHooks;
import org.folio.tlib.postgres.PgCqlDefinition;
import org.folio.tlib.postgres.PgCqlQuery;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldAlwaysMatches;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldNumber;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldText;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldUuid;
import org.folio.tlib.util.TenantUtil;

public class ReservoirService implements RouterCreator, TenantInitHooks {

  private static final String ENTITY_ID_NOT_FOUND_PATTERN = "%s %s not found";
  private static final String MODULE_LABEL = "Module";
  private static final Logger log = LogManager.getLogger(ReservoirService.class);

  private final ModuleVersionReporter moduleVersionReporter;

  public ReservoirService(ModuleVersionReporter moduleVersionReporter) {
    this.moduleVersionReporter = moduleVersionReporter;
  }

  Future<Void> getServiceInfo(RoutingContext ctx) {
    String baseUrl = ctx.request().absoluteURI().replaceAll("/reservoir$", "");
    JsonObject links = new JsonObject()
        .put("clusters", baseUrl + "/reservoir/clusters")
        .put("configMatchKeys", baseUrl + "/reservoir/config/matchkeys")
        .put("configModules", baseUrl + "/reservoir/config/modules")
        .put("configOai", baseUrl + "/reservoir/config/oai")
        .put("oai", baseUrl + "/reservoir/oai")
        .put("pmhClients", baseUrl + "/reservoir/pmh-clients")
        .put("records", baseUrl + "/reservoir/records")
        .put("sru", baseUrl + "/reservoir/sru")
        .put("upload", baseUrl + "/reservoir/upload")
        ;
    JsonObject response = new JsonObject()
        .put("links", links)
        .put("name", moduleVersionReporter.getModule())
        .put("version", moduleVersionReporter.getVersion())
        .put("revision", moduleVersionReporter.getCommitId());
    HttpResponse.responseJson(ctx, 200).end(response.encode());
    return Future.succeededFuture();
  }

  Future<Void> putGlobalRecords(RoutingContext ctx) {
    try {
      Storage storage = new Storage(ctx);
      HttpServerRequest request = ctx.request();
      request.pause();
      return storage.updateGlobalRecords(ctx.vertx(), new LargeJsonReadStream(request))
          .onSuccess(res -> {
            JsonArray ar = new JsonArray();
            // global ids and match keys here ...
            HttpResponse.responseJson(ctx, 200).end(ar.encode());
          });
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  Future<Void> reloadCodeModule(RoutingContext ctx) {
    final String id = Util.getPathParameter(ctx, "id");
    final Storage storage = new Storage(ctx);
    final String tenant = TenantUtil.tenant(ctx);
    return storage.selectCodeModuleEntity(id)
      .compose(res -> {
        if (res == null) {
          HttpResponse.responseError(ctx, 404,
              String.format(ENTITY_ID_NOT_FOUND_PATTERN, MODULE_LABEL, id));
          return Future.succeededFuture();
        }
        return new CodeModuleEntity.CodeModuleBuilder(res.asJson())
          .resolve(ctx.vertx())
          .compose(cm -> ModuleCache.getInstance().lookup(ctx.vertx(), tenant, cm)
            .compose(module -> storage.updateCodeModuleEntity(cm))
            .compose(x -> ctx.response().setStatusCode(204).end())
          );
      });
  }

  Future<Void> deleteCodeModule(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "id");
    Storage storage = new Storage(ctx);
    return storage.deleteCodeModuleEntity(id)
        .onSuccess(res -> {
          if (Boolean.FALSE.equals(res)) {
            HttpResponse.responseError(ctx, 404,
                String.format(ENTITY_ID_NOT_FOUND_PATTERN, MODULE_LABEL, id));
            return;
          }
          ModuleCache.getInstance().purge(TenantUtil.tenant(ctx), id);
          ctx.response().setStatusCode(204).end();
        }).mapEmpty();
  }

  static PgCqlDefinition createDefinitionBase() {
    PgCqlDefinition def = PgCqlDefinition.create();
    def.addField(CqlFields.CQL_ALL_RECORDS.getCqlName(), new PgCqlFieldAlwaysMatches());
    return def;
  }

  static PgCqlDefinition createDefinitionGlobalRecords() {
    PgCqlDefinition def = createDefinitionBase();
    def.addField(CqlFields.ID.getCqlName(),
      new PgCqlFieldUuid());
    def.addField(CqlFields.GLOBAL_ID.getCqlName(),
      new PgCqlFieldUuid().withColumn(CqlFields.GLOBAL_ID.getSqllName()));
    def.addField(CqlFields.LOCAL_ID.getCqlName(),
      new PgCqlFieldText().withExact().withColumn(CqlFields.LOCAL_ID.getSqllName()));
    def.addField(CqlFields.SOURCE_ID.getCqlName(),
      new PgCqlFieldText().withExact().withColumn(CqlFields.SOURCE_ID.getSqllName()));
    def.addField(CqlFields.SOURCE_VERSION.getCqlName(),
      new PgCqlFieldNumber().withColumn(CqlFields.SOURCE_VERSION.getSqllName()));
    return def;
  }

  Future<Void> deleteGlobalRecords(RoutingContext ctx) {
    PgCqlDefinition definition = createDefinitionGlobalRecords();
    String query = Util.getQueryParameterQuery(ctx);
    if (query == null) {
      failHandler(400, ctx, "Must specify query for delete records");
      return Future.succeededFuture();
    }
    PgCqlQuery pgCqlQuery = definition.parse(query);
    Storage storage = new Storage(ctx);
    return storage.deleteGlobalRecords(pgCqlQuery.getWhereClause())
        .onSuccess(x -> ctx.response().setStatusCode(204).end());
  }

  Future<Void> getGlobalRecords(RoutingContext ctx) {
    PgCqlDefinition definition = createDefinitionGlobalRecords();
    PgCqlQuery pgCqlQuery = definition.parse(Util.getQueryParameterQuery(ctx));
    Storage storage = new Storage(ctx);
    return storage.getGlobalRecords(ctx, pgCqlQuery.getWhereClause(),
        pgCqlQuery.getOrderByClause());
  }

  Future<Void> getGlobalRecord(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "globalId");
    Storage storage = new Storage(ctx);
    return storage.getGlobalRecord(id)
        .onSuccess(res -> {
          if (res == null) {
            HttpResponse.responseError(ctx, 404, id);
            return;
          }
          HttpResponse.responseJson(ctx, 200).end(res.encode());
        })
        .mapEmpty();
  }

  void matchKeyNotFound(RoutingContext ctx, String id) {
    HttpResponse.responseError(ctx, 404, "MatchKey " + id + " not found");
  }

  Future<Void> getClusters(RoutingContext ctx) {
    PgCqlDefinition definition = createDefinitionBase();
    definition.addField(CqlFields.MATCH_VALUE.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.MATCH_VALUE.getQualifiedSqlName()));
    definition.addField(CqlFields.CLUSTER_ID.getCqlName(),
        new PgCqlFieldUuid().withColumn(CqlFields.CLUSTER_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.GLOBAL_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.GLOBAL_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.LOCAL_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.LOCAL_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.SOURCE_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.SOURCE_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.SOURCE_VERSION.getCqlName(),
        new PgCqlFieldNumber().withColumn(CqlFields.SOURCE_VERSION.getQualifiedSqlName()));

    PgCqlQuery pgCqlQuery = definition.parse(Util.getQueryParameterQuery(ctx));
    String matchKeyId = Util.getQueryParameter(ctx, "matchkeyid");
    if (matchKeyId == null) {
      failHandler(400, ctx, "Missing required query parameter: matchkeyid");
      return Future.succeededFuture();
    }
    Storage storage = new Storage(ctx);
    return storage.selectMatchKeyConfig(matchKeyId).compose(conf -> {
      if (conf == null) {
        matchKeyNotFound(ctx, matchKeyId);
        return Future.succeededFuture();
      }
      return storage.getClusters(ctx, matchKeyId,
          pgCqlQuery.getWhereClause(), pgCqlQuery.getOrderByClause());
    });
  }

  Future<Void> touchClusters(RoutingContext ctx) {
    PgCqlDefinition definition = createDefinitionBase();
    definition.addField(CqlFields.MATCHKEY_ID.getCqlName(),
      new PgCqlFieldText().withExact().withColumn(CqlFields.MATCHKEY_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.CLUSTER_ID.getCqlName(),
        new PgCqlFieldUuid().withColumn(CqlFields.CLUSTER_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.SOURCE_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.SOURCE_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.SOURCE_VERSION.getCqlName(),
        new PgCqlFieldNumber().withColumn(CqlFields.SOURCE_VERSION.getQualifiedSqlName()));

    PgCqlQuery pgCqlQuery = definition.parse(Util.getQueryParameterQuery(ctx));
    Storage storage = new Storage(ctx);
    return storage.touchClusters(pgCqlQuery)
        .map(count -> new JsonObject().put("count", count))
        .onSuccess(res -> {
          ctx.response().putHeader("Content-Type", "application/json");
          ctx.response().end(res.encode());
        })
        .mapEmpty();
  }

  Future<Void> getCluster(RoutingContext ctx) {
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    RequestParameter requestParameter = validatedRequest.getPathParameters().get("clusterId");
    String clusterId = requestParameter.getString();
    Storage storage = new Storage(ctx);
    return storage.getClusterById(UUID.fromString(clusterId))
        .onSuccess(res -> {
          if (res.getJsonArray("records").isEmpty()) {
            HttpResponse.responseError(ctx, 404, clusterId);
            return;
          }
          HttpResponse.responseJson(ctx, 200).end(res.encode());
        })
        .mapEmpty();
  }

  static String getMethod(JsonObject config) {
    String method = config.getString("method");
    return method;
  }

  static Future<String> checkMatcher(Storage storage, JsonObject config) {
    String matcherProp = config.getString("matcher");
    if (matcherProp != null) {
      ModuleInvocation invocation = new ModuleInvocation(matcherProp);
      return storage.selectCodeModuleEntity(invocation.getModuleName())
        .compose(entity -> {
          if (entity == null) {
            return Future.failedFuture("Matcher module '" + invocation.getModuleName()
              + "' does not exist");
          }
          return Future.succeededFuture(matcherProp);
        });
    } else {
      return Future.succeededFuture(null);
    }
  }

  Future<Void> postConfigMatchKey(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject request = validatedRequest.getBody().getJsonObject();
    String id = request.getString("id");
    String method = getMethod(request);
    String update = request.getString("update", "ingest");
    JsonObject params = request.getJsonObject("params");
    return checkMatcher(storage, request)
        .compose(matcher ->
            storage.insertMatchKeyConfig(id, matcher, method, params, update))
        .onSuccess(res ->
          HttpResponse.responseJson(ctx, 201)
            .putHeader("Location", ctx.request().absoluteURI() + "/" + id)
            .end(request.encode())
        );
  }

  Future<Void> getConfigMatchKey(RoutingContext ctx) {
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    String id = validatedRequest.getPathParameters().get("id").getString();
    Storage storage = new Storage(ctx);
    return storage.selectMatchKeyConfig(id)
        .onSuccess(res -> {
          if (res == null) {
            matchKeyNotFound(ctx, id);
            return;
          }
          HttpResponse.responseJson(ctx, 200).end(res.encode());
        })
        .mapEmpty();
  }

  Future<Void> putConfigMatchKey(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject request = validatedRequest.getBody().getJsonObject();
    String id = request.getString("id");
    String method = getMethod(request);
    String update = request.getString("update", "ingest");
    JsonObject params = request.getJsonObject("params");
    return checkMatcher(storage, request)
       .compose(matcher -> storage.updateMatchKeyConfig(id, matcher, method, params, update))
        .onSuccess(res -> {
          if (Boolean.FALSE.equals(res)) {
            matchKeyNotFound(ctx, id);
            return;
          }
          ctx.response().setStatusCode(204).end();
        })
        .mapEmpty();
  }

  Future<Void> deleteConfigMatchKey(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "id");
    Storage storage = new Storage(ctx);
    return storage.deleteMatchKeyConfig(id)
        .onSuccess(res -> {
          if (Boolean.FALSE.equals(res)) {
            matchKeyNotFound(ctx, id);
            return;
          }
          ctx.response().setStatusCode(204).end();
        })
        .mapEmpty();
  }

  Future<Void> getConfigMatchKeys(RoutingContext ctx) {
    PgCqlDefinition definition = createDefinitionBase();
    definition.addField(CqlFields.ID.getCqlName(), new PgCqlFieldText().withExact());
    definition.addField(CqlFields.METHOD.getCqlName(), new PgCqlFieldText().withExact());
    definition.addField(CqlFields.MATCHER.getCqlName(), new PgCqlFieldText().withExact());

    PgCqlQuery pgCqlQuery = definition.parse(Util.getQueryParameterQuery(ctx));

    Storage storage = new Storage(ctx);
    return storage.getMatchKeyConfigs(ctx, pgCqlQuery.getWhereClause(),
        pgCqlQuery.getOrderByClause());
  }

  Future<Void> initializeMatchKey(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "id");
    Storage storage = new Storage(ctx);
    return storage.initializeMatchKey(ctx.vertx(), id)
        .onSuccess(res -> {
          if (res == null) {
            matchKeyNotFound(ctx, id);
            return;
          }
          HttpResponse.responseJson(ctx, 200).end(res.encode());
        })
        .mapEmpty();
  }

  Future<Void> statsMatchKey(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "id");
    Storage storage = new Storage(ctx);
    return storage.selectMatchKeyConfig(id)
        .compose(conf -> {
          if (conf == null) {
            matchKeyNotFound(ctx, id);
            return Future.succeededFuture();
          }
          return storage.statsMatchKey(id)
              .onSuccess(res -> HttpResponse.responseJson(ctx, 200).end(res.encode()))
              .mapEmpty();
        });
  }

  //start modules, move to another class

  Future<Void> postCodeModule(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject request = validatedRequest.getBody().getJsonObject();
    return new CodeModuleEntity.CodeModuleBuilder(request)
      .resolve(ctx.vertx())
      .compose(cm -> ModuleCache.getInstance().lookup(ctx.vertx(), TenantUtil.tenant(ctx), cm)
        .compose(module -> storage.insertCodeModuleEntity(cm))
        .compose(res ->
          HttpResponse.responseJson(ctx, 201)
              .putHeader("Location", ctx.request().absoluteURI() + "/" + cm.getId())
              .end(cm.asJson(true).encode())
        )
    );
  }

  Future<Void> getCodeModule(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "id");
    Storage storage = new Storage(ctx);
    return storage.selectCodeModuleEntity(id)
        .onSuccess(e -> {
          if (e == null) {
            HttpResponse.responseError(ctx, 404,
                String.format(ENTITY_ID_NOT_FOUND_PATTERN, MODULE_LABEL, id));
            return;
          }
          HttpResponse.responseJson(ctx, 200).end(e.asJson(true).encode());
        })
        .mapEmpty();
  }

  Future<Void> putCodeModule(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject request = validatedRequest.getBody().getJsonObject();
    return new CodeModuleEntity.CodeModuleBuilder(request)
      .resolve(ctx.vertx())
      .compose(cm -> ModuleCache.getInstance().lookup(ctx.vertx(), TenantUtil.tenant(ctx), cm)
        .compose(module -> storage.updateCodeModuleEntity(cm))
        .compose(res -> {
          if (Boolean.FALSE.equals(res)) {
            HttpResponse.responseError(ctx, 404,
                String.format(ENTITY_ID_NOT_FOUND_PATTERN, MODULE_LABEL, cm.getId()));
            return Future.succeededFuture();
          }
          return ctx.response().setStatusCode(204).end();
        }
      )
    );
  }

  Future<Void> getCodeModules(RoutingContext ctx) {
    PgCqlDefinition definition = createDefinitionBase();
    definition.addField(CqlFields.ID.getCqlName(), new PgCqlFieldText().withExact());
    definition.addField(CqlFields.FUNCTION.getCqlName(), new PgCqlFieldText().withExact());

    PgCqlQuery pgCqlQuery = definition.parse(Util.getQueryParameterQuery(ctx));

    Storage storage = new Storage(ctx);
    return storage.selectCodeModuleEntities(ctx, pgCqlQuery.getWhereClause(),
        pgCqlQuery.getOrderByClause());
  }


  //end modules

  //oai config

  Future<Void> getOaiConfig(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    return storage.selectOaiConfig()
        .onSuccess(res -> {
          if (res == null) {
            HttpResponse.responseError(ctx, 404, "OAI config not found");
            return;
          }
          HttpResponse.responseJson(ctx, 200).end(res.encode());
        })
        .mapEmpty();
  }

  Future<Void> putOaiConfig(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject request = validatedRequest.getBody().getJsonObject();
    return storage.updateOaiConfig(request)
        .onSuccess(res -> {
          if (Boolean.FALSE.equals(res)) {
            HttpResponse.responseError(ctx, 400, "OAI config not updated");
            return;
          }
          ctx.response().setStatusCode(204).end();
        })
        .mapEmpty();
  }

  Future<Void> deleteOaiConfig(RoutingContext ctx) {
    Storage storage = new Storage(ctx);
    return storage.deleteOaiConfig()
        .onSuccess(res -> ctx.response().setStatusCode(204).end());
  }

  //end oai config

  static void failHandler(RoutingContext ctx) {
    Throwable t = ctx.failure();
    if (t instanceof io.vertx.ext.web.handler.HttpException) {
      io.vertx.ext.web.handler.HttpException he = (io.vertx.ext.web.handler.HttpException) t;
      failHandler(he.getStatusCode(), ctx, he.getCause());
      return;
    }
    failHandler(500, ctx, t);
  }

  static void failHandler(RoutingContext ctx, Throwable e) {
    if (e instanceof ForbiddenException) {
      failHandler(403, ctx, e);
    } else {
      failHandler(400, ctx, e);
    }
  }

  static void failHandler(int statusCode, RoutingContext ctx, Throwable e) {
    log.error(e.getMessage(), e);
    failHandler(statusCode, ctx, e.getMessage());
  }

  static void failHandler(int statusCode, RoutingContext ctx, String msg) {
    HttpServerResponse response = ctx.response();
    if (response.headWritten()) {
      if (!response.ended()) {
        ctx.response().end();
      }
      return;
    }
    response.setStatusCode(statusCode);
    response.putHeader("Content-Type", "text/plain");
    response.end(msg != null ? msg : "Failure");
  }

  private void add(RouterBuilder routerBuilder, String operationId,
      Function<RoutingContext, Future<Void>> function) {
    add(routerBuilder, operationId, function, true);
  }

  private void add(RouterBuilder routerBuilder, String operationId,
      Function<RoutingContext, Future<Void>> function, boolean doValidation) {

    if (routerBuilder.getRoute(operationId) == null) {
      throw new IllegalArgumentException("Unknown operationId: " + operationId);
    }
    routerBuilder
        .getRoute(operationId)
        .setDoValidation(doValidation)
        .addHandler(ctx -> {
          try {
            function.apply(ctx)
                .onFailure(cause -> failHandler(400, ctx, cause));
          } catch (Exception t) {
            failHandler(400, ctx, t);
          }
        })
        .addFailureHandler(ReservoirService::failHandler);
  }

  @Override
  public Future<Router> createRouter(Vertx vertx) {
    OaiPmhClientService oaiPmhClient = new OaiPmhClientService(vertx);
    UploadService uploadService = new UploadService();
    return OpenAPIContract.from(vertx, "openapi/reservoir.yaml")
        .map(contract -> {
          RouterBuilder routerBuilder = RouterBuilder.create(vertx, contract);
          // routerBuilder.rootHandler(BodyHandler.create().setBodyLimit(65536)
          //     .setHandleFileUploads(false));
          add(routerBuilder, "getServiceInfo", this::getServiceInfo);
          add(routerBuilder, "getGlobalRecords", this::getGlobalRecords, false);
          add(routerBuilder, "deleteGlobalRecords", this::deleteGlobalRecords, false);
          add(routerBuilder, "getGlobalRecord", this::getGlobalRecord);
          add(routerBuilder, "postConfigMatchKey", this::postConfigMatchKey);
          add(routerBuilder, "getConfigMatchKey", this::getConfigMatchKey);
          add(routerBuilder, "putConfigMatchKey", this::putConfigMatchKey);
          add(routerBuilder, "deleteConfigMatchKey", this::deleteConfigMatchKey);
          add(routerBuilder, "getConfigMatchKeys", this::getConfigMatchKeys);
          add(routerBuilder, "initializeMatchKey", this::initializeMatchKey);
          add(routerBuilder, "statsMatchKey", this::statsMatchKey);
          add(routerBuilder, "getClusters", this::getClusters, false);
          add(routerBuilder, "touchClusters", this::touchClusters, false);
          add(routerBuilder, "getCluster", this::getCluster);
          add(routerBuilder, "postCodeModule", this::postCodeModule);
          add(routerBuilder, "getCodeModule", this::getCodeModule);
          add(routerBuilder, "putCodeModule", this::putCodeModule);
          add(routerBuilder, "deleteCodeModule", this::deleteCodeModule);
          add(routerBuilder, "reloadCodeModule", this::reloadCodeModule);
          add(routerBuilder, "getCodeModules", this::getCodeModules);
          add(routerBuilder, "getOaiConfig", this::getOaiConfig);
          add(routerBuilder, "putOaiConfig", this::putOaiConfig);
          add(routerBuilder, "deleteOaiConfig", this::deleteOaiConfig);
          add(routerBuilder, "postOaiPmhClient", oaiPmhClient::post);
          add(routerBuilder, "getOaiPmhClient", oaiPmhClient::get);
          add(routerBuilder, "putOaiPmhClient", oaiPmhClient::put);
          add(routerBuilder, "deleteOaiPmhClient", oaiPmhClient::delete);
          add(routerBuilder, "getCollectionOaiPmhClient", oaiPmhClient::getCollection);
          add(routerBuilder, "startOaiPmhClient", oaiPmhClient::start);
          add(routerBuilder, "stopOaiPmhClient", oaiPmhClient::stop);
          add(routerBuilder, "statusOaiPmhClient", oaiPmhClient::status);

          Router router = Router.router(vertx);

          router.get("/reservoir/oai").handler(ctx ->
              OaiService.get(ctx).onFailure(cause -> failHandler(400, ctx, cause)));

          router.get("/reservoir/sru").handler(ctx ->
              SruService.get(ctx).onFailure(cause -> failHandler(400, ctx, cause)));

          // this endpoint is streaming, and we handle it without OpenAPI and validation
          router.put("/reservoir/records").handler(ctx ->
              putGlobalRecords(ctx).onFailure(cause -> failHandler(400, ctx, cause)));
          router.route("/reservoir/upload")
              .method(HttpMethod.POST).method(HttpMethod.PUT).handler(ctx ->
              uploadService.uploadRecords(ctx).onFailure(cause -> failHandler(ctx, cause)));
          //upload page
          router.route("/reservoir/upload-form/*")
              .handler(StaticHandler.create());

          router.route("/*").subRouter(routerBuilder.createRouter());
          return router;
        });
  }

  @Override
  public Future<Void> postInit(Vertx vertx, String tenant, JsonObject tenantAttributes) {
    if (!tenantAttributes.containsKey("module_to")) {
      return Future.succeededFuture(); // doing nothing for disable
    }
    Storage storage = new Storage(vertx, tenant, HttpMethod.POST);
    return storage.init();
  }
}
