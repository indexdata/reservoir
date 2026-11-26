package com.indexdata.reservoir.server;

import com.indexdata.reservoir.module.ModuleCache;
import com.indexdata.reservoir.module.ModuleExecutable;
import com.indexdata.reservoir.module.ModuleInvocation;
import com.indexdata.reservoir.server.db.ClusterRepository;
import com.indexdata.reservoir.server.db.CodeModuleRepository;
import com.indexdata.reservoir.server.db.GlobalRecordRepository;
import com.indexdata.reservoir.server.db.OaiConfigRepository;
import com.indexdata.reservoir.server.db.OaiPmhClientRepository;
import com.indexdata.reservoir.server.db.PoolConfigRepository;
import com.indexdata.reservoir.server.db.PoolInitJobRepository;
import com.indexdata.reservoir.server.db.RowStreamer;
import com.indexdata.reservoir.server.db.SchemaMigrator;
import com.indexdata.reservoir.server.db.SqlBuilder;
import com.indexdata.reservoir.server.db.Tables;
import com.indexdata.reservoir.server.db.TenantDb;
import com.indexdata.reservoir.server.entity.Cluster;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import com.indexdata.reservoir.server.entity.GlobalRecord;
import com.indexdata.reservoir.server.entity.GlobalRecordJson;
import com.indexdata.reservoir.server.entity.PoolConfig;
import com.indexdata.reservoir.server.entity.PoolStats;
import com.indexdata.reservoir.server.metrics.IngestMetrics;
import com.indexdata.reservoir.server.metrics.IngestMetricsNop;
import com.indexdata.reservoir.util.ReadStreamConsumer;
import com.indexdata.reservoir.util.SourceId;
import com.indexdata.reservoir.util.readstream.LargeJsonReadStream;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tenant-scoped storage facade that wires repositories over {@link TenantDb} and orchestrates
 * multi-repository ingest and clustering transactions. Contains no raw SQL or HTTP dependencies.
 */
public class Storage {
  public static final String GLOBAL_RECORDS_TABLE = Tables.GLOBAL_RECORDS;
  public static final String POOL_CONFIG_TABLE = Tables.POOL_CONFIG;
  public static final String CLUSTER_META_TABLE = Tables.CLUSTER_META;
  public static final String CLUSTER_RECORDS_TABLE = Tables.CLUSTER_RECORDS;
  public static final String CLUSTER_VALUES_TABLE = Tables.CLUSTER_VALUES;
  public static final String MODULE_TABLE = Tables.MODULE;
  public static final String OAI_CONFIG_TABLE = Tables.OAI_CONFIG;
  public static final String OAI_PMH_CLIENTS_TABLE = Tables.OAI_PMH_CLIENTS;
  public static final String POOL_INITIALIZATION_JOBS_TABLE = Tables.POOL_INITIALIZATION_JOBS;

  private static final Logger log = LogManager.getLogger(Storage.class);
  private static final int MATCHVALUE_MAX_LENGTH = 600; // < 2704 / 4

  private final TenantDb db;
  private final Vertx vertx;
  private final String tenant;
  private final PoolInitJobRepository poolInitJobRepository;
  private final GlobalRecordRepository globalRecordRepository;
  private final PoolConfigRepository poolConfigRepository;
  private final CodeModuleRepository codeModuleRepository;
  private final OaiPmhClientRepository oaiPmhClientRepository;
  private final OaiConfigRepository oaiConfigRepository;
  private final ClusterRepository clusterRepository;
  InitializationDiagnostics initializationDiagnostics;
  static int sqlStreamFetchSize = 50;

  /**
   * Create storage service for tenant.
   * @param vertx Vert.x handle
   * @param tenant tenant
   * @param method HTTP method for selecting read or write pool
   */
  public Storage(Vertx vertx, String tenant, HttpMethod method) {
    this.db = TenantDb.forTenant(vertx, tenant, method);
    this.tenant = tenant;
    this.vertx = vertx;
    this.poolInitJobRepository = new PoolInitJobRepository(db);
    this.globalRecordRepository = new GlobalRecordRepository(db);
    this.poolConfigRepository = new PoolConfigRepository(db);
    this.codeModuleRepository = new CodeModuleRepository(db);
    this.oaiPmhClientRepository = new OaiPmhClientRepository(db);
    this.oaiConfigRepository = new OaiConfigRepository(db);
    this.clusterRepository = new ClusterRepository(db);
  }

  static String getPoolKey(HttpMethod method) {
    return TenantDb.poolKey(method);
  }

  public TenantDb db() {
    return db;
  }

  public PoolInitJobRepository poolInitJobs() {
    return poolInitJobRepository;
  }

  public GlobalRecordRepository globalRecords() {
    return globalRecordRepository;
  }

  public PoolConfigRepository poolConfigs() {
    return poolConfigRepository;
  }

  public CodeModuleRepository codeModules() {
    return codeModuleRepository;
  }

  public OaiPmhClientRepository oaiPmhClients() {
    return oaiPmhClientRepository;
  }

  public OaiConfigRepository oaiConfigs() {
    return oaiConfigRepository;
  }

  public ClusterRepository clusters() {
    return clusterRepository;
  }

  public String getTenant() {
    return tenant;
  }

  /**
   * Run versioned SQL migrations for this tenant and resolve any pending URL-backed code modules.
   */
  public Future<Void> init() {
    return SchemaMigrator.migrate(db)
        .compose(_ -> resolveCodeModuleTable(vertx));
  }

  private Future<Boolean> upsertGlobalRecord(int retryCount, String localIdentifier,
      SourceId sourceId, int sourceVersion, JsonObject payload, List<MatcherResult> matcherResults,
      IngestMetrics ingestMetrics, Function<SqlConnection, Future<Void>> beforeWrite) {
    return db.withTransaction(conn -> beforeWrite.apply(conn).compose(_ ->
            upsertGlobalRecord(conn, localIdentifier, sourceId, sourceVersion,
                payload, matcherResults, ingestMetrics)))
        // Retry unique violations in a new transaction, rechecking ownership on each attempt.
        .recover(e -> {
          if (retryCount == 0 || !(e instanceof PgException pgException)
              || !"23505".equals(pgException.getSqlState())) {
            return Future.failedFuture(e);
          }
          return upsertGlobalRecord(retryCount - 1, localIdentifier, sourceId, sourceVersion,
              payload, matcherResults, ingestMetrics, beforeWrite);
        });
  }

  Future<Boolean> upsertGlobalRecord(SqlConnection conn, String localIdentifier,
      SourceId sourceId, int sourceVersion, JsonObject payload, List<MatcherResult> matcherResults,
      IngestMetrics ingestMetrics) {
    UUID candidateId = UUID.randomUUID();
    return globalRecordRepository.upsert(conn, candidateId, localIdentifier,
            sourceId, sourceVersion, payload)
        .compose(id -> updateMatchKeyValues(conn, id, matcherResults, ingestMetrics)
            .map(_ -> id.equals(candidateId)));
  }

  Future<Void> deleteGlobalRecord(String localIdentifier, SourceId sourceId, int sourceVersion) {
    return deleteGlobalRecord(localIdentifier, sourceId, sourceVersion,
        _ -> Future.succeededFuture());
  }

  private Future<Void> deleteGlobalRecord(String localIdentifier, SourceId sourceId,
      int sourceVersion, Function<SqlConnection, Future<Void>> beforeWrite) {
    return db.withTransaction(conn -> beforeWrite.apply(conn)
        .compose(_ -> clusterRepository.touchForDeletedRecord(
            conn, localIdentifier, sourceId, sourceVersion))
        .compose(_ -> globalRecordRepository.deleteByKey(
            conn, localIdentifier, sourceId, sourceVersion)));
  }

  /**
   * Insert/update/delete global record.
   * @return async result with TRUE=inserted, FALSE=updated, null=deleted
   */
  Future<Boolean> ingestGlobalRecord(Vertx vertx,
      SourceId sourceId, int sourceVersion, JsonObject globalRecord,
      List<IngestMatcher> ingestMatchers, IngestMetrics ingestMetrics) {
    return ingestGlobalRecord(vertx, sourceId, sourceVersion, globalRecord, ingestMatchers,
        ingestMetrics, _ -> Future.succeededFuture());
  }

  /** Ingest with a guard executed inside the transaction that writes the record. */
  Future<Boolean> ingestGlobalRecord(Vertx vertx,
      SourceId sourceId, int sourceVersion, JsonObject globalRecord,
      List<IngestMatcher> ingestMatchers, IngestMetrics ingestMetrics,
      Function<SqlConnection, Future<Void>> beforeWrite) {
    long startTime = System.nanoTime();
    return ingestGlobalRecord2(vertx, sourceId, sourceVersion, globalRecord,
        ingestMatchers, ingestMetrics, beforeWrite)
        .onComplete(_ -> ingestMetrics.recordStoring(
            System.nanoTime() - startTime, TimeUnit.NANOSECONDS));
  }

  private Future<Boolean> ingestGlobalRecord2(Vertx vertx,
      SourceId sourceId, int sourceVersion, JsonObject globalRecord,
      List<IngestMatcher> ingestMatchers, IngestMetrics ingestMetrics,
      Function<SqlConnection, Future<Void>> beforeWrite) {

    final String localIdentifier = globalRecord.getString(GlobalRecordJson.LOCAL_ID);
    if (localIdentifier == null) {
      ingestMetrics.incrementRecordsIgnored();
      return Future.failedFuture("localId required");
    }
    if (Boolean.TRUE.equals(globalRecord.getBoolean("delete"))) {
      return deleteGlobalRecord(localIdentifier, sourceId, sourceVersion, beforeWrite)
          .map(_ -> {
            ingestMetrics.incrementRecordsDeleted();
            return null;
          });
    }
    final JsonObject payload = globalRecord.getJsonObject(GlobalRecordJson.PAYLOAD);
    if (payload == null) {
      ingestMetrics.incrementRecordsIgnored();
      return Future.failedFuture("payload required");
    }
    if (sourceId == null) {
      ingestMetrics.incrementRecordsIgnored();
      return Future.failedFuture("sourceId required");
    }
    List<Future<MatcherResult>> futures = new ArrayList<>(ingestMatchers.size());
    for (IngestMatcher ingestMatcher : ingestMatchers) {
      futures.add(runMatcher(ingestMatcher, ingestMetrics, globalRecord));
    }
    return Future.all(futures).compose(cf -> {
      List<MatcherResult> results = new ArrayList<>(cf.size());
      for (int i = 0; i < cf.size(); i++) {
        results.add(cf.resultAt(i));
      }
      return upsertGlobalRecord(results.size(), localIdentifier, sourceId, sourceVersion,
          payload, results, ingestMetrics, beforeWrite)
          .map(inserted -> {
            if (inserted) {
              ingestMetrics.incrementRecordsInserted();
            } else {
              ingestMetrics.incrementRecordsUpdated();
            }
            return inserted;
          });
    });
  }

  Future<MatcherResult> runMatcher(IngestMatcher ingestMatcher, IngestMetrics ingestMetrics,
      JsonObject globalRecord) {
    MatcherResult result = new MatcherResult();
    result.poolId = ingestMatcher.poolId;
    result.keys = new HashSet<>();
    var startTime = System.nanoTime();
    if (ingestMatcher.moduleExecutables != null) {
      JsonObject matcherInput = ingestMatcher.onlyPayload
          ? globalRecord.getJsonObject(GlobalRecordJson.PAYLOAD) : globalRecord;
      List<Future<Collection<String>>> futures = new ArrayList<>();
      for (ModuleExecutable executable : ingestMatcher.moduleExecutables) {
        futures.add(executable.executeAsCollection(matcherInput));
      }
      return Future.all(futures)
          .map(results -> {
            for (int i = 0; i < results.size(); i++) {
              Collection<String> values = results.resultAt(i);
              values.forEach(k -> result.keys.add(k.length() > MATCHVALUE_MAX_LENGTH
                  ? k.substring(0, MATCHVALUE_MAX_LENGTH) : k));
            }
            ingestMetrics.recordMatcher(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
            return result;
          });
    }
    return Future.succeededFuture(result);
  }

  Future<Void> updateMatchKeyValues(SqlConnection conn, UUID globalId,
      List<MatcherResult> matcherResults, IngestMetrics ingestMetrics) {
    List<Future<Void>> futures = new ArrayList<>(matcherResults.size());
    for (MatcherResult matcherResult : matcherResults) {
      futures.add(updateClusterForRecord(conn, globalId, matcherResult));
    }
    return Future.all(futures).mapEmpty();
  }

  Future<IngestMatcher> createIngestMatcher(PoolConfig poolConfig, Vertx vertx) {
    IngestMatcher ingestMatcher = new IngestMatcher();
    ingestMatcher.onlyPayload = poolConfig.onlyPayload();
    ingestMatcher.poolId = poolConfig.id();
    List<ModuleInvocation> invocations = poolConfig.matchers();
    if (invocations.isEmpty()) {
      return Future.failedFuture("pool config must include 'matcher'");
    }
    List<Future<ModuleExecutable>> futures = new ArrayList<>(invocations.size());
    for (ModuleInvocation invocation : invocations) {
      futures.add(codeModuleRepository.findById(db.client(), invocation.moduleName())
          .compose(entity -> {
            if (entity.isEmpty()) {
              return Future.failedFuture(
                  "Module '" + invocation.moduleName()
                      + "' does not exist for '" + invocation + "'");
            }
            return ModuleCache.getInstance().lookup(tenant, entity.get())
                .map(module -> new ModuleExecutable(module, invocation, vertx));
          }));
    }
    return Future.all(futures).map(results -> {
      ingestMatcher.moduleExecutables = new ModuleExecutable[results.size()];
      for (int i = 0; i < results.size(); i++) {
        ingestMatcher.moduleExecutables[i] = results.resultAt(i);
      }
      return ingestMatcher;
    });
  }

  Future<List<IngestMatcher>> createIngestMatchers(List<PoolConfig> poolConfigs, Vertx vertx) {
    List<Future<IngestMatcher>> futures = new ArrayList<>();
    for (PoolConfig poolConfig : poolConfigs) {
      if (poolConfig.isManualUpdate()) {
        continue;
      }
      futures.add(createIngestMatcher(poolConfig, vertx));
    }
    return Future.all(futures).map(composite -> {
      List<IngestMatcher> ingestMatchers = new ArrayList<>(composite.size());
      for (int i = 0; i < composite.size(); i++) {
        ingestMatchers.add(composite.resultAt(i));
      }
      return ingestMatchers;
    });
  }

  Future<List<IngestMatcher>> availableIngestMatchers(Vertx vertx) {
    return getAvailablePoolConfigs()
        .compose(poolConfigs -> createIngestMatchers(poolConfigs, vertx));
  }

  <T> Future<T> initializationStage(String stage, Supplier<Future<T>> action) {
    return initializationDiagnostics == null ? action.get()
        : initializationDiagnostics.measure(stage, action);
  }

  <T> Future<T> initializationTransaction(Function<SqlConnection, Future<T>> action) {
    if (initializationDiagnostics == null) {
      return db.withTransaction(action);
    }
    long start = System.nanoTime();
    long[] finish = {0};
    return db.withTransaction(connection -> {
      initializationDiagnostics.elapsed("acquire.begin", start, true);
      return action.apply(connection).andThen(_ -> finish[0] = System.nanoTime());
    }).andThen(result -> {
      if (finish[0] != 0) {
        initializationDiagnostics.elapsed("transaction.finish", finish[0], result.succeeded());
      }
    });
  }

  private Future<RowSet<Row>> clusterQuery(SqlConnection connection, String stage,
      String sql, Tuple parameters) {
    if (initializationDiagnostics == null) {
      return connection.preparedQuery(sql).execute(parameters);
    }
    return initializationStage(stage, () -> connection.preparedQuery(sql).execute(parameters))
        .map(rows -> {
          if (initializationDiagnostics != null) {
            initializationDiagnostics.rows(stage, rows.rowCount());
          }
          return rows;
        });
  }

  Future<Void> initializeRecord(SqlConnection connection, IngestMatcher matcher,
      IngestMetrics metrics, UUID globalId, JsonObject record) {
    return initializationStage("matcher", () -> runMatcher(matcher, metrics, record))
        .compose(result -> {
          if (initializationDiagnostics != null) {
            initializationDiagnostics.keys(result.keys.size());
          }
          return updateClusterForRecord(connection, globalId, result);
        }).andThen(result -> {
          if (initializationDiagnostics != null) {
            initializationDiagnostics.recordDone(result.succeeded());
          }
        });
  }

  Future<Set<UUID>> updateClusterValues(SqlConnection conn, UUID newClusterId,
      MatcherResult matcherResult) {
    Set<UUID> clustersFound = new HashSet<>();
    for (String key : matcherResult.keys) {
      if (key.isEmpty()) {
        return Future.succeededFuture(clustersFound);
      }
    }
    SqlBuilder.SqlQuery lookup = clusterRepository.buildClusterValueLookupQuery(
        matcherResult.poolId, matcherResult.keys);
    Set<String> foundKeys = new HashSet<>();
    String stage = "lookup.keys=" + matcherResult.keys.size();
    Future<Void> explanation = initializationDiagnostics == null ? Future.succeededFuture()
        : initializationDiagnostics.explain(conn, lookup.sql(), lookup.params(),
            matcherResult.keys.size());
    return explanation
        .compose(_ -> clusterQuery(conn, stage, lookup.sql(), lookup.params()))
        .map(rowSet -> {
          rowSet.forEach(row -> {
            foundKeys.add(row.getString("match_value"));
            clustersFound.add(row.getUUID("cluster_id"));
          });
          if (initializationDiagnostics != null) {
            initializationDiagnostics.clusters(clustersFound.size());
          }
          return clustersFound.isEmpty() ? newClusterId : clustersFound.iterator().next();
        })
        .compose(clusterId -> {
          List<String> newValues = new ArrayList<>();
          for (String key : matcherResult.keys) {
            if (!foundKeys.contains(key)) {
              newValues.add(key);
            }
          }
          return clusterRepository.addValuesToCluster(
              conn, clusterId, matcherResult.poolId, newValues, this::clusterQuery);
        })
        .map(clustersFound);
  }

  Future<Void> updateClusterForRecord(SqlConnection conn, UUID globalId,
      MatcherResult matcherResult) {
    if (matcherResult.keys.isEmpty()) {
      return clusterRepository.removeClusterRecord(
          conn, globalId, matcherResult.poolId, this::clusterQuery);
    }
    UUID newClusterId = UUID.randomUUID();
    return updateClusterValues(conn, newClusterId, matcherResult)
        .compose(clustersFound -> {
          Iterator<UUID> iterator = clustersFound.iterator();
          if (!iterator.hasNext()) {
            return clusterRepository.createMetaEntry(
                conn, newClusterId, matcherResult.poolId, this::clusterQuery);
          }
          return clusterRepository.updateMetaEntries(conn, clustersFound, this::clusterQuery)
              .compose(_ -> {
                UUID targetClusterId = iterator.next();
                if (!iterator.hasNext()) {
                  return Future.succeededFuture(targetClusterId);
                }
                List<UUID> mergedIds = new ArrayList<>();
                iterator.forEachRemaining(mergedIds::add);
                return clusterRepository.mergeClusters(
                        conn, targetClusterId, mergedIds, this::clusterQuery)
                    .map(targetClusterId);
              });
        })
        .compose(clusterId -> clusterRepository.upsertClusterRecord(
            conn, globalId, matcherResult.poolId, clusterId, this::clusterQuery));
  }

  /** Update/insert set of global records from a JSON read stream. */
  public Future<Void> updateGlobalRecords(Vertx vertx, LargeJsonReadStream request) {
    return availableIngestMatchers(vertx)
        .compose(ingestMatchers -> new ReadStreamConsumer<JsonObject, Void>()
            .consume(request, r -> {
              JsonObject topRecord = request.topLevelObject();
              SourceId sourceId = new SourceId(
                  topRecord.getString(GlobalRecordJson.SOURCE_ID));
              IngestMetrics ingestMetrics = IngestMetrics.create().withSource(sourceId);
              Integer sourceVersion = topRecord.getInteger(
                  GlobalRecordJson.SOURCE_VERSION, 1);
              r.put(GlobalRecordJson.SOURCE_ID, sourceId.toString());
              r.put(GlobalRecordJson.SOURCE_VERSION, sourceVersion);
              return ingestGlobalRecord(vertx, sourceId,
                  sourceVersion, r, ingestMatchers, ingestMetrics)
                  .mapEmpty();
            }));
  }

  /** Get available pool configurations. */
  public Future<List<PoolConfig>> getAvailablePoolConfigs() {
    return poolConfigRepository.findAll(db.client());
  }

  /** Delete global records matching a parsed CQL query and update cluster datestamps. */
  public Future<Void> deleteGlobalRecords(ParsedCql cql) {
    return clusterRepository.touchForDeletedRecordsWhere(
            db.client(), cql.where(), cql.negatedSourceVersionWhere())
        .compose(updated -> {
          log.info("Number of meta records updated = {}", updated);
          return globalRecordRepository.deleteByWhere(db.client(), cql.where());
        });
  }

  /** Get cluster by cluster identifier. */
  public Future<Optional<Cluster>> getClusterById(UUID clusterId) {
    return db.withConnection(conn -> clusterRepository.findById(conn, clusterId));
  }

  /** Get pool statistics. */
  public Future<PoolStats> statsPool(String id) {
    return clusterRepository.computeStats(id, sqlStreamFetchSize);
  }

  Future<Integer> recalculateMatchKeyValueTable(SqlConnection connection,
      IngestMatcher ingestMatcher) {
    AtomicInteger totalRecords = new AtomicInteger();
    IngestMetrics ingestMetrics = new IngestMetricsNop();
    long[] fetchStart = {initializationDiagnostics == null ? 0 : System.nanoTime()};
    return RowStreamer.streamAsync(connection, globalRecordRepository.selectAllQuery(),
        sqlStreamFetchSize,
        row -> {
          if (initializationDiagnostics != null) {
            initializationDiagnostics.elapsed("fetch.wait", fetchStart[0], true);
          }
          totalRecords.incrementAndGet();
          GlobalRecord rec = GlobalRecordRepository.fromRow(row);
          return initializeRecord(connection, ingestMatcher, ingestMetrics,
              rec.id(), GlobalRecordJson.toJson(rec))
              .onSuccess(_ -> {
                if (initializationDiagnostics != null) {
                  fetchStart[0] = System.nanoTime();
                }
              });
        },
        () -> {
          if (initializationDiagnostics != null) {
            initializationDiagnostics.elapsed("fetch.wait", fetchStart[0], true);
          }
          return Future.succeededFuture(totalRecords.get());
        });
  }

  /**
   * Synchronously initialize a pool (populate clusters for all global records).
   * @return number of records processed, or null if pool not found
   */
  public Future<Integer> initializePool(Vertx vertx, String id) {
    initializationDiagnostics = InitializationDiagnostics.create(
        tenant, id, UUID.randomUUID().toString());
    return db.withConnection(connection ->
        poolConfigRepository.findById(connection, id)
            .compose(optConfig -> {
              if (optConfig.isEmpty()) {
                return Future.succeededFuture(null);
              }
              return createIngestMatcher(optConfig.get(), vertx)
                  .compose(matcher -> recalculateMatchKeyValueTable(connection, matcher));
            })
    ).andThen(result -> {
      if (initializationDiagnostics != null) {
        String status;
        if (result.failed()) {
          status = "failed";
        } else {
          status = result.result() == null ? "not_found" : "completed";
        }
        initializationDiagnostics.summary(status);
      }
    });
  }

  Future<Void> resolveCodeModuleTable(Vertx vertx) {
    return codeModuleRepository.findAll(db.client())
        .compose(modules -> {
          Future<Void> result = Future.succeededFuture();
          for (CodeModuleEntity cm : modules) {
            if (cm.hasScript()) {
              continue;
            }
            if (!cm.hasUrl()) {
              log.warn("Code module '{}' has no URL to resolve", cm.id());
              continue;
            }
            result = result
                .compose(_ -> CodeModuleResolver.resolve(vertx, cm)
                        .compose(resolved -> codeModuleRepository.update(db.client(), resolved)
                            .mapEmpty()),
                    e -> {
                      log.warn("Failed to resolve code module '{}': {}", cm.id(), e.getMessage());
                      return Future.succeededFuture();
                    });
          }
          return result;
        });
  }

  Future<ModuleExecutable> getTransformer(Vertx vertx, ModuleInvocation invocation) {
    return codeModuleRepository.findById(db.client(), invocation.moduleName())
        .compose(entity -> {
          if (entity.isEmpty()) {
            return Future.failedFuture("Transformer module '"
                + invocation.moduleName() + "' not found");
          }
          return ModuleCache.getInstance().lookup(tenant, entity.get())
              .map(mod -> new ModuleExecutable(mod, invocation, vertx));
        });
  }

  Future<ModuleExecutable> getTransformerOai(Vertx vertx) {
    return oaiConfigRepository.find(db.client())
        .compose(oaiCfgOpt -> {
          if (oaiCfgOpt.isEmpty()) {
            return Future.succeededFuture(null);
          }
          String transformerProp = oaiCfgOpt.get().getString("transformer");
          if (transformerProp == null) {
            return Future.succeededFuture(null);
          }
          return getTransformer(vertx, ModuleInvocation.parse(transformerProp));
        });
  }
}
