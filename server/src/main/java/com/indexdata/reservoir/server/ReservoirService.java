package com.indexdata.reservoir.server;

import com.indexdata.reservoir.module.ModuleCache;
import com.indexdata.reservoir.module.ModuleInvocation;
import com.indexdata.reservoir.server.db.CodeModuleRepository;
import com.indexdata.reservoir.server.db.GlobalRecordRepository;
import com.indexdata.reservoir.server.db.PoolConfigRepository;
import com.indexdata.reservoir.server.entity.ClusterJson;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import com.indexdata.reservoir.server.entity.CodeModuleJson;
import com.indexdata.reservoir.server.entity.GlobalRecordJson;
import com.indexdata.reservoir.server.entity.PoolConfig;
import com.indexdata.reservoir.server.entity.PoolConfigJson;
import com.indexdata.reservoir.server.entity.PoolStatsJson;
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
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.openapi.contract.OpenAPIContract;
import io.vertx.openapi.validation.RequestParameter;
import io.vertx.openapi.validation.ValidatedRequest;
import io.vertx.pgclient.PgException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.HttpResponse;
import org.folio.okapi.common.ModuleVersionReporter;
import org.folio.tlib.RouterCreator;
import org.folio.tlib.TenantInitHooks;
import org.folio.tlib.postgres.PgCqlDefinition;
import org.folio.tlib.postgres.PgCqlException;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldAlwaysMatches;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldNumber;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldText;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldUuid;

public class ReservoirService implements RouterCreator, TenantInitHooks {

  private static final String ENTITY_ID_NOT_FOUND_PATTERN = "%s %s not found";
  private static final String MODULE_LABEL = "Module";
  private static final String DEPRECATION_DATE = "@1786520473";
  private static final String UNIQUE_VIOLATION = "23505";
  private static final Logger log = LogManager.getLogger(ReservoirService.class);

  private final ModuleVersionReporter moduleVersionReporter;
  private final String defaultTenant;

  public ReservoirService(ModuleVersionReporter moduleVersionReporter, String defaultTenant) {
    this.moduleVersionReporter = moduleVersionReporter;
    this.defaultTenant = defaultTenant;
  }

  private static Storage storageFor(RoutingContext ctx) {
    return new Storage(ctx.vertx(), Tenant.get(ctx), ctx.request().method());
  }

  Future<Void> getServiceInfo(RoutingContext ctx) {
    String baseUrl = ctx.request().absoluteURI().replaceAll("/reservoir$", "");
    JsonObject links = new JsonObject()
        .put("clusters", baseUrl + "/reservoir/clusters")
        .put("configPools", baseUrl + "/reservoir/config/pools")
        .put("configModules", baseUrl + "/reservoir/config/modules")
        .put("configOai", baseUrl + "/reservoir/config/oai")
        .put("oai", baseUrl + "/reservoir/oai")
        .put("pmhClients", baseUrl + "/reservoir/pmh-clients")
        .put("records", baseUrl + "/reservoir/records")
        .put("sru", baseUrl + "/reservoir/sru")
        .put("upload", baseUrl + "/reservoir/upload");
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
      Storage storage = storageFor(ctx);
      HttpServerRequest request = ctx.request();
      request.pause();
      return storage.updateGlobalRecords(ctx.vertx(), new LargeJsonReadStream(request))
          .compose(_ -> {
            JsonArray ar = new JsonArray();
            return HttpResponse.responseJson(ctx, 200).end(ar.encode());
          });
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  Future<Void> reloadCodeModule(RoutingContext ctx) {
    final String id = Util.getPathParameter(ctx, "id");
    final Storage storage = storageFor(ctx);
    final String tenant = Tenant.get(ctx);
    return storage.codeModules().findById(storage.db().client(), id)
        .compose(res -> {
          if (res.isEmpty()) {
            HttpResponse.responseError(ctx, 404,
                String.format(ENTITY_ID_NOT_FOUND_PATTERN, MODULE_LABEL, id));
            return Future.succeededFuture();
          }
          return CodeModuleResolver.resolve(ctx.vertx(), res.get())
              .compose(cm -> ModuleCache.getInstance().lookup(tenant, cm)
                  .compose(_ -> storage.codeModules().update(storage.db().client(), cm))
                  .compose(_ -> ctx.response().setStatusCode(204).end()));
        });
  }

  Future<Void> deleteCodeModule(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "id");
    Storage storage = storageFor(ctx);
    return storage.codeModules().delete(storage.db().client(), id)
        .compose(deleted -> {
          if (Boolean.FALSE.equals(deleted)) {
            HttpResponse.responseError(ctx, 404,
                String.format(ENTITY_ID_NOT_FOUND_PATTERN, MODULE_LABEL, id));
            return Future.succeededFuture();
          }
          ModuleCache.getInstance().purge(Tenant.get(ctx), id);
          return ctx.response().setStatusCode(204).end();
        });
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
        new PgCqlFieldUuid().withColumn(CqlFields.GLOBAL_ID.getSqlName()));
    def.addField(CqlFields.LOCAL_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.LOCAL_ID.getSqlName()));
    def.addField(CqlFields.SOURCE_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.SOURCE_ID.getSqlName()));
    def.addField(CqlFields.SOURCE_VERSION.getCqlName(),
        new PgCqlFieldNumber().withColumn(CqlFields.SOURCE_VERSION.getSqlName()));
    return def;
  }

  Future<Void> deleteGlobalRecords(RoutingContext ctx) {
    try {
      PgCqlDefinition definition = createDefinitionGlobalRecords();
      String query = Util.getQueryParameterQuery(ctx);
      if (query == null) {
        failHandler(400, ctx, "Must specify query for delete records");
        return Future.succeededFuture();
      }
      ParsedCql parsed = ParsedCql.parseWithSourceVersionNegation(definition, query);
      Storage storage = storageFor(ctx);
      return storage.deleteGlobalRecords(parsed)
          .compose(_ -> ctx.response().setStatusCode(204).end());
    } catch (PgCqlException e) {
      throw badRequest(e);
    }
  }

  Future<Void> getGlobalRecords(RoutingContext ctx) {
    try {
      PgCqlDefinition definition = createDefinitionGlobalRecords();
      ParsedCql parsed = ParsedCql.parse(definition, Util.getQueryParameterQuery(ctx));
      Storage storage = storageFor(ctx);
      return JsonListResponder.streamTable(
          ctx,
          storage.db(),
          GlobalRecordRepository.COLUMNS,
          storage.db().tables().globalRecords(),
          parsed.where(),
          parsed.orderBy(),
          "items",
          Storage.sqlStreamFetchSize,
          row -> GlobalRecordJson.toJson(GlobalRecordRepository.fromRow(row)));
    } catch (PgCqlException e) {
      throw badRequest(e);
    }
  }

  Future<Void> getGlobalRecord(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "globalId");
    Storage storage = storageFor(ctx);
    return storage.globalRecords().findById(storage.db().client(), id)
        .compose(res -> {
          if (res.isEmpty()) {
            HttpResponse.responseError(ctx, 404, id);
            return Future.succeededFuture();
          }
          return HttpResponse.responseJson(ctx, 200)
              .end(GlobalRecordJson.toJson(res.get()).encode());
        });
  }

  private static boolean isLegacyPoolRequest(RoutingContext ctx) {
    if (ctx.request().path().contains("/config/matchkeys")) {
      return true;
    }
    if (ctx.request().path().contains("/config/pools")) {
      return false;
    }
    return Util.getQueryParameter(ctx, "poolId") == null
        && Util.getQueryParameter(ctx, "matchkeyid") != null;
  }

  private static void warnDeprecatedEndpoint(RoutingContext ctx) {
    String path = ctx.request().path();
    String replacement = path.replace("/config/matchkeys", "/config/pools");
    log.warn("Deprecated endpoint used: tenant={} method={} path={} userAgent=\"{}\"; "
            + "use {} instead",
        Tenant.get(ctx), ctx.request().method(), path, userAgent(ctx), replacement);
  }

  private static void warnDeprecatedQueryParameter(RoutingContext ctx, String parameter,
      String replacement) {
    log.warn("Deprecated query parameter used: tenant={} method={} path={} parameter={} "
            + "userAgent=\"{}\"; use {} instead",
        Tenant.get(ctx), ctx.request().method(), ctx.request().path(), parameter, userAgent(ctx),
        replacement);
  }

  private static void warnDeprecatedCqlIndex(RoutingContext ctx, String index,
      String replacement) {
    log.warn("Deprecated CQL index used: tenant={} method={} path={} index={} userAgent=\"{}\"; "
            + "use {} instead",
        Tenant.get(ctx), ctx.request().method(), ctx.request().path(), index, userAgent(ctx),
        replacement);
  }

  private static String userAgent(RoutingContext ctx) {
    return sanitizeUserAgent(ctx.request().getHeader("User-Agent"));
  }

  static String sanitizeUserAgent(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    String sanitized = value.replace('\r', ' ').replace('\n', ' ');
    return sanitized.length() <= 256 ? sanitized : sanitized.substring(0, 256);
  }

  Future<Void> poolNotFound(RoutingContext ctx, String id) {
    String resource = isLegacyPoolRequest(ctx) ? "MatchKey" : "Pool";
    HttpResponse.responseError(ctx, 404, resource + " " + id + " not found");
    return Future.succeededFuture();
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

    ParsedCql parsed;
    try {
      parsed = ParsedCql.parse(definition, Util.getQueryParameterQuery(ctx));
    } catch (PgCqlException e) {
      throw badRequest(e);
    }
    String requestedPoolId = Util.getQueryParameter(ctx, "poolId");
    if (requestedPoolId == null) {
      String legacyPoolId = Util.getQueryParameter(ctx, "matchkeyid");
      if (legacyPoolId != null) {
        warnDeprecatedQueryParameter(ctx, "matchkeyid", "poolId");
        requestedPoolId = legacyPoolId;
      }
    }
    if (requestedPoolId == null) {
      failHandler(400, ctx, "Missing required query parameter: poolId");
      return Future.succeededFuture();
    }
    final String poolId = requestedPoolId;
    Storage storage = storageFor(ctx);
    return storage.poolConfigs().findById(storage.db().client(), poolId).compose(conf -> {
      if (conf.isEmpty()) {
        return poolNotFound(ctx, poolId);
      }
      JsonListResponder.PageParams page = JsonListResponder.PageParams.from(ctx);
      return storage.db().withConnection(conn ->
          storage.clusters().findClusterIdsPage(conn, poolId, parsed, page.offset(), page.limit())
              .compose(ids -> JsonListResponder.streamByChunks(
                  ctx,
                  "items",
                  ids,
                  Storage.sqlStreamFetchSize,
                  chunk -> storage.clusters().loadClustersByIds(conn, chunk)
                      .map(clusters -> clusters.stream().map(ClusterJson::toJson).toList()),
                  page.exactCount()
                      ? () -> storage.clusters().countClusters(conn, poolId, parsed)
                      : null)));
    });
  }

  Future<Void> touchClusters(RoutingContext ctx) {
    PgCqlDefinition definition = createDefinitionBase();
    definition.addField(CqlFields.POOL_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.POOL_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.MATCHKEY_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.MATCHKEY_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.CLUSTER_ID.getCqlName(),
        new PgCqlFieldUuid().withColumn(CqlFields.CLUSTER_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.SOURCE_ID.getCqlName(),
        new PgCqlFieldText().withExact().withColumn(CqlFields.SOURCE_ID.getQualifiedSqlName()));
    definition.addField(CqlFields.SOURCE_VERSION.getCqlName(),
        new PgCqlFieldNumber().withColumn(CqlFields.SOURCE_VERSION.getQualifiedSqlName()));

    String query = Util.getQueryParameterQuery(ctx);
    ParsedCql parsed;
    try {
      parsed = ParsedCql.parse(definition, query);
    } catch (PgCqlException e) {
      throw badRequest(e);
    }
    if (parsed.where() == null
        || !parsed.filtersOn(CqlFields.SOURCE_ID)
        || !parsed.filtersOnAny(CqlFields.POOL_ID, CqlFields.MATCHKEY_ID)) {
      failHandler(400, ctx, "query too broad, must at least contain 'poolId' and 'sourceId'");
      return Future.succeededFuture();
    }
    if (parsed.uses(CqlFields.MATCHKEY_ID)) {
      warnDeprecatedCqlIndex(ctx, CqlFields.MATCHKEY_ID.getCqlName(),
          CqlFields.POOL_ID.getCqlName());
    }
    Storage storage = storageFor(ctx);
    return storage.clusters().touchByCql(storage.db().client(), parsed.where())
        .map(count -> new JsonObject().put("count", count))
        .compose(res -> {
          ctx.response().putHeader("Content-Type", "application/json");
          return ctx.response().end(res.encode());
        });
  }

  Future<Void> getCluster(RoutingContext ctx) {
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    RequestParameter requestParameter = validatedRequest.getPathParameters().get("clusterId");
    String clusterId = requestParameter.getString();
    Storage storage = storageFor(ctx);
    return storage.getClusterById(UUID.fromString(clusterId))
        .compose(res -> {
          if (res.isEmpty()) {
            HttpResponse.responseError(ctx, 404, clusterId);
            return Future.succeededFuture();
          }
          return HttpResponse.responseJson(ctx, 200)
              .end(ClusterJson.toJson(res.get()).encode());
        });
  }

  static Future<String> checkMatcher(Storage storage, PoolConfig poolConfig) {
    List<ModuleInvocation> matcherInvocations = poolConfig.matchers();
    if (matcherInvocations.isEmpty()) {
      return Future.succeededFuture(null);
    }
    List<Future<Void>> futures = new ArrayList<>(matcherInvocations.size());
    for (ModuleInvocation invocation : matcherInvocations) {
      futures.add(storage.codeModules().findById(storage.db().client(), invocation.moduleName())
          .compose(entity -> {
            if (entity.isEmpty()) {
              return Future.failedFuture(badRequest(
                  "Matcher module '" + invocation.moduleName() + "' does not exist"));
            }
            return Future.succeededFuture();
          }));
    }
    return Future.all(futures).map(poolConfig.matcherString());
  }

  private static PoolConfig createPoolConfig(RoutingContext ctx, JsonObject request) {
    try {
      return PoolConfigJson.fromJson(request);
    } catch (IllegalArgumentException e) {
      if (isLegacyPoolRequest(ctx) && e.getMessage() != null) {
        throw badRequest(new IllegalArgumentException(
            e.getMessage().replace("PoolConfig", "MatchKeyConfig"), e));
      }
      throw badRequest(e);
    }
  }

  Future<Void> postPool(RoutingContext ctx) {
    Storage storage = storageFor(ctx);
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject request = validatedRequest.getBody().getJsonObject();

    PoolConfig poolConfig = createPoolConfig(ctx, request);
    return checkMatcher(storage, poolConfig)
        .compose(_ -> storage.poolConfigs().insert(storage.db().client(), poolConfig))
        .compose(_ ->
            HttpResponse.responseJson(ctx, 201)
                .putHeader("Location", ctx.request().absoluteURI() + "/" + poolConfig.id())
                .end(request.encode()))
        .recover(ReservoirService::mapUniqueViolation);
  }

  Future<Void> getPool(RoutingContext ctx) {
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    String id = validatedRequest.getPathParameters().get("id").getString();
    Storage storage = storageFor(ctx);
    return storage.poolConfigs().findById(storage.db().client(), id)
        .compose(res -> {
          if (res.isEmpty()) {
            return poolNotFound(ctx, id);
          }
          return HttpResponse.responseJson(ctx, 200)
              .end(PoolConfigJson.toJson(res.get()).encode());
        });
  }

  Future<Void> putPool(RoutingContext ctx) {
    Storage storage = storageFor(ctx);
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject request = validatedRequest.getBody().getJsonObject();
    PoolConfig poolConfig = createPoolConfig(ctx, request);
    return checkMatcher(storage, poolConfig)
        .compose(_ -> storage.poolConfigs().update(storage.db().client(), poolConfig))
        .compose(updated -> {
          if (Boolean.FALSE.equals(updated)) {
            return poolNotFound(ctx, poolConfig.id());
          }
          return ctx.response().setStatusCode(204).end();
        });
  }

  Future<Void> deletePool(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "id");
    Storage storage = storageFor(ctx);
    return storage.poolConfigs().delete(storage.db().client(), id)
        .compose(deleted -> {
          if (Boolean.FALSE.equals(deleted)) {
            return poolNotFound(ctx, id);
          }
          return ctx.response().setStatusCode(204).end();
        });
  }

  Future<Void> getPools(RoutingContext ctx) {
    PgCqlDefinition definition = createDefinitionBase();
    definition.addField(CqlFields.ID.getCqlName(), new PgCqlFieldText().withExact());
    definition.addField(CqlFields.METHOD.getCqlName(), new PgCqlFieldText().withExact());
    definition.addField(CqlFields.MATCHER.getCqlName(),
        new CqlFieldTermMapper(new PgCqlFieldText().withExact(), term -> {
          try {
            return ModuleInvocation.normalizeList(term);
          } catch (IllegalArgumentException e) {
            return term;
          }
        }, null));

    ParsedCql parsed;
    try {
      parsed = ParsedCql.parse(definition, Util.getQueryParameterQuery(ctx));
    } catch (PgCqlException e) {
      throw badRequest(e);
    }

    Storage storage = storageFor(ctx);
    String resultProperty = isLegacyPoolRequest(ctx) ? "matchKeys" : "pools";
    return JsonListResponder.streamTable(
        ctx,
        storage.db(),
        PoolConfigRepository.COLUMNS,
        storage.db().tables().poolConfig(),
        parsed.where(),
        parsed.orderBy(),
        resultProperty,
        Storage.sqlStreamFetchSize,
        row -> PoolConfigJson.toJson(PoolConfigRepository.fromRow(row)));
  }

  Future<Void> initializePool(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "id");
    Storage storage = storageFor(ctx);
    return storage.initializePool(ctx.vertx(), id)
        .compose(totalRecords -> {
          if (totalRecords == null) {
            return poolNotFound(ctx, id);
          }
          JsonObject response = new JsonObject().put("totalRecords", totalRecords);
          return HttpResponse.responseJson(ctx, 200).end(response.encode());
        });
  }

  Future<Void> statsPool(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "id");
    Storage storage = storageFor(ctx);
    return storage.poolConfigs().findById(storage.db().client(), id)
        .compose(conf -> {
          if (conf.isEmpty()) {
            return poolNotFound(ctx, id);
          }
          return storage.statsPool(id)
              .compose(stats -> HttpResponse.responseJson(ctx, 200)
                  .end(PoolStatsJson.toJson(stats).encode()));
        });
  }

  Future<Void> postCodeModule(RoutingContext ctx) {
    Storage storage = storageFor(ctx);
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject request = validatedRequest.getBody().getJsonObject();
    Future<CodeModuleEntity> resolved;
    try {
      resolved = CodeModuleResolver.resolve(ctx.vertx(), CodeModuleJson.fromJson(request));
    } catch (RuntimeException e) {
      throw badRequest(e);
    }
    return resolved
        .compose(cm -> ModuleCache.getInstance().lookup(Tenant.get(ctx), cm).map(cm))
        .recover(cause -> Future.failedFuture(badRequest(cause)))
        .compose(cm -> storage.codeModules().insert(storage.db().client(), cm)
            .recover(ReservoirService::mapUniqueViolation)
            .compose(_ ->
                HttpResponse.responseJson(ctx, 201)
                    .putHeader("Location", ctx.request().absoluteURI() + "/" + cm.id())
                    .end(CodeModuleJson.toJson(cm).encode())));
  }

  Future<Void> getCodeModule(RoutingContext ctx) {
    String id = Util.getPathParameter(ctx, "id");
    Storage storage = storageFor(ctx);
    return storage.codeModules().findById(storage.db().client(), id)
        .compose(e -> {
          if (e.isEmpty()) {
            HttpResponse.responseError(ctx, 404,
                String.format(ENTITY_ID_NOT_FOUND_PATTERN, MODULE_LABEL, id));
            return Future.succeededFuture();
          }
          return HttpResponse.responseJson(ctx, 200)
              .end(CodeModuleJson.toJson(e.get()).encode());
        });
  }

  Future<Void> putCodeModule(RoutingContext ctx) {
    Storage storage = storageFor(ctx);
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject request = validatedRequest.getBody().getJsonObject();
    Future<CodeModuleEntity> resolved;
    try {
      resolved = CodeModuleResolver.resolve(ctx.vertx(), CodeModuleJson.fromJson(request));
    } catch (RuntimeException e) {
      throw badRequest(e);
    }
    return resolved
        .compose(cm -> ModuleCache.getInstance().lookup(Tenant.get(ctx), cm).map(cm))
        .recover(cause -> Future.failedFuture(badRequest(cause)))
        .compose(cm -> storage.codeModules().update(storage.db().client(), cm)
            .compose(updated -> {
              if (Boolean.FALSE.equals(updated)) {
                HttpResponse.responseError(ctx, 404,
                    String.format(ENTITY_ID_NOT_FOUND_PATTERN, MODULE_LABEL, cm.id()));
                return Future.succeededFuture();
              }
              return ctx.response().setStatusCode(204).end();
            }));
  }

  Future<Void> getCodeModules(RoutingContext ctx) {
    PgCqlDefinition definition = createDefinitionBase();
    definition.addField(CqlFields.ID.getCqlName(), new PgCqlFieldText().withExact());
    definition.addField(CqlFields.FUNCTION.getCqlName(), new PgCqlFieldText().withExact());

    ParsedCql parsed;
    try {
      parsed = ParsedCql.parse(definition, Util.getQueryParameterQuery(ctx));
    } catch (PgCqlException e) {
      throw badRequest(e);
    }

    Storage storage = storageFor(ctx);
    return JsonListResponder.streamTable(
        ctx,
        storage.db(),
        CodeModuleRepository.COLUMNS,
        storage.db().tables().module(),
        parsed.where(),
        parsed.orderBy(),
        "modules",
        Storage.sqlStreamFetchSize,
        row -> CodeModuleJson.toJson(CodeModuleRepository.fromRow(row)));
  }

  Future<Void> getOaiConfig(RoutingContext ctx) {
    Storage storage = storageFor(ctx);
    return storage.oaiConfigs().find(storage.db().client())
        .compose(res -> {
          if (res.isEmpty()) {
            HttpResponse.responseError(ctx, 404, "OAI config not found");
            return Future.succeededFuture();
          }
          return HttpResponse.responseJson(ctx, 200).end(res.get().encode());
        });
  }

  Future<Void> putOaiConfig(RoutingContext ctx) {
    Storage storage = storageFor(ctx);
    ValidatedRequest validatedRequest = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    JsonObject request = validatedRequest.getBody().getJsonObject();
    return storage.oaiConfigs().upsert(storage.db().client(), request)
        .compose(_ -> ctx.response().setStatusCode(204).end());
  }

  Future<Void> deleteOaiConfig(RoutingContext ctx) {
    Storage storage = storageFor(ctx);
    return storage.oaiConfigs().delete(storage.db().client())
        .compose(_ -> ctx.response().setStatusCode(204).end());
  }

  private static HttpException badRequest(String message) {
    return badRequest(new IllegalArgumentException(message));
  }

  private static HttpException badRequest(Throwable cause) {
    return new HttpException(400, cause);
  }

  private static <T> Future<T> mapUniqueViolation(Throwable cause) {
    if (cause instanceof PgException pgException
        && UNIQUE_VIOLATION.equals(pgException.getSqlState())) {
      return Future.failedFuture(badRequest(cause));
    }
    return Future.failedFuture(cause);
  }

  static void failHandler(RoutingContext ctx) {
    Throwable t = ctx.failure();
    if (t instanceof HttpException he) {
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
        .addHandler(ctx -> function.apply(ctx).onFailure(ctx::fail))
        .addFailureHandler(ReservoirService::failHandler);
  }

  private void addDeprecatedPoolRoute(RouterBuilder routerBuilder, String operationId,
      Function<RoutingContext, Future<Void>> function) {
    add(routerBuilder, operationId, ctx -> {
      ctx.response().putHeader("Deprecation", DEPRECATION_DATE);
      warnDeprecatedEndpoint(ctx);
      return function.apply(ctx);
    });
  }

  @Override
  public Future<Router> createRouter(Vertx vertx) {
    OaiPmhClientService oaiPmhClient = new OaiPmhClientService(vertx);
    PoolInitializationService poolInitialization = new PoolInitializationService();
    UploadService uploadService = new UploadService();
    return OpenAPIContract.from(vertx, "openapi/reservoir.yaml")
        .map(contract -> {
          RouterBuilder routerBuilder = RouterBuilder.create(vertx, contract);
          add(routerBuilder, "getServiceInfo", this::getServiceInfo);
          add(routerBuilder, "getGlobalRecords", this::getGlobalRecords, false);
          add(routerBuilder, "deleteGlobalRecords", this::deleteGlobalRecords, false);
          add(routerBuilder, "getGlobalRecord", this::getGlobalRecord);
          add(routerBuilder, "postPool", this::postPool);
          add(routerBuilder, "getPool", this::getPool);
          add(routerBuilder, "putPool", this::putPool);
          add(routerBuilder, "deletePool", this::deletePool);
          add(routerBuilder, "getPools", this::getPools);
          add(routerBuilder, "initializePool", this::initializePool);
          add(routerBuilder, "initializationsPoolGetAll", poolInitialization::getAll);
          add(routerBuilder, "initializationsPoolPost", poolInitialization::post);
          add(routerBuilder, "initializationsPoolGet", poolInitialization::get);
          add(routerBuilder, "initializationsPoolDelete", poolInitialization::delete);
          add(routerBuilder, "statsPool", this::statsPool);
          addDeprecatedPoolRoute(routerBuilder, "postConfigMatchKey", this::postPool);
          addDeprecatedPoolRoute(routerBuilder, "getConfigMatchKey", this::getPool);
          addDeprecatedPoolRoute(routerBuilder, "putConfigMatchKey", this::putPool);
          addDeprecatedPoolRoute(routerBuilder, "deleteConfigMatchKey", this::deletePool);
          addDeprecatedPoolRoute(routerBuilder, "getConfigMatchKeys", this::getPools);
          addDeprecatedPoolRoute(routerBuilder, "initializeMatchKey", this::initializePool);
          addDeprecatedPoolRoute(routerBuilder, "statsMatchKey", this::statsPool);
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

          router.route().order(Integer.MIN_VALUE).handler(ctx -> {
            Tenant.populate(ctx, defaultTenant);
            ctx.next();
          });

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
          // upload page
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
