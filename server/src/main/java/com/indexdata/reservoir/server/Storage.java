package com.indexdata.reservoir.server;

import com.indexdata.reservoir.matchkey.MatchKeyMethod;
import com.indexdata.reservoir.module.ModuleCache;
import com.indexdata.reservoir.module.ModuleExecutable;
import com.indexdata.reservoir.module.ModuleInvocation;
import com.indexdata.reservoir.server.entity.ClusterBuilder;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import com.indexdata.reservoir.server.metrics.IngestMetrics;
import com.indexdata.reservoir.server.metrics.IngestMetricsNop;
import com.indexdata.reservoir.util.ReadStreamConsumer;
import com.indexdata.reservoir.util.SourceId;
import com.indexdata.reservoir.util.readstream.LargeJsonReadStream;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.RowStream;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.tlib.postgres.PgCqlQuery;
import org.folio.tlib.postgres.TenantPgPool;
import org.folio.tlib.util.TenantUtil;


// Define a constant instead of duplicating this literal
@java.lang.SuppressWarnings({"squid:S1192"})
public class Storage {
  public static final String GLOBAL_RECORDS_TABLE = "global_records";
  public static final String MATCH_KEY_CONFIG_TABLE = "match_key_config";
  public static final String CLUSTER_META_TABLE = "cluster_meta";
  public static final String CLUSTER_RECORDS_TABLE = "cluster_records";
  public static final String CLUSTER_VALUES_TABLE = "cluster_values";
  public static final String MODULE_TABLE = "module";
  public static final String OAI_CONFIG_TABLE = "oai_config";
  public static final String OAI_PMH_CLIENTS_TABLE = "oai_pmh_clients";

  private static final Logger log = LogManager.getLogger(Storage.class);
  private static final String CREATE_IF_NO_EXISTS = "CREATE TABLE IF NOT EXISTS ";
  private static final int MATCHVALUE_MAX_LENGTH = 600; // < 2704 / 4

  final TenantPgPool pool;
  final String globalRecordTable;
  final String matchKeyConfigTable;
  final String clusterRecordTable;
  final String clusterValueTable;
  final String clusterMetaTable;
  final String moduleTable;
  final String oaiConfigTable;
  final String oaiPmhClientTable;
  private final String tenant;
  static int sqlStreamFetchSize = 50;

  /**
   * Create storage service for tenant.
   * @param vertx Vert.x hande
   * @param tenant tenant
   * @param method HTTP method for selecting read or write pool
   */
  public Storage(Vertx vertx, String tenant, HttpMethod method) {
    final String key = getPoolKey(method);
    this.pool = TenantPgPool.pool(vertx, tenant, key);
    this.tenant = tenant;
    this.globalRecordTable = pool.getSchema() + "." + GLOBAL_RECORDS_TABLE;
    this.matchKeyConfigTable = pool.getSchema() + "." + MATCH_KEY_CONFIG_TABLE;
    this.clusterRecordTable = pool.getSchema() + "." + CLUSTER_RECORDS_TABLE;
    this.clusterValueTable = pool.getSchema() + "." + CLUSTER_VALUES_TABLE;
    this.clusterMetaTable = pool.getSchema() + "." + CLUSTER_META_TABLE;
    this.moduleTable = pool.getSchema() + "." + MODULE_TABLE;
    this.oaiConfigTable = pool.getSchema() + "." + OAI_CONFIG_TABLE;
    this.oaiPmhClientTable = pool.getSchema() + "." + OAI_PMH_CLIENTS_TABLE;
  }

  static String getPoolKey(HttpMethod method) {
    if (method.equals(HttpMethod.GET)) {
      return "read";
    }
    return "write";
  }

  public Storage(RoutingContext ctx) {
    this(ctx.vertx(), TenantUtil.tenant(ctx), ctx.request().method());
  }

  public TenantPgPool getPool() {
    return pool;
  }

  public String getClusterMetaTable() {
    return clusterMetaTable;
  }

  public String getGlobalRecordTable() {
    return globalRecordTable;
  }

  public String getClusterRecordTable() {
    return clusterRecordTable;
  }

  public String getClusterValuesTable() {
    return clusterValueTable;
  }

  public String getModuleTable() {
    return moduleTable;
  }

  public String getOaiPmhClientTable() {
    return oaiPmhClientTable;
  }

  public String getTenant() {
    return tenant;
  }

  /**
   * Prepares storage with tables, etc.
   * @return async result.
   */
  public Future<Void> init() {
    return pool.execute(List.of(
            "SET search_path TO " + pool.getSchema(),
            CREATE_IF_NO_EXISTS + globalRecordTable
                + "(id uuid NOT NULL PRIMARY KEY,"
                + " local_id VARCHAR NOT NULL,"
                + " source_id VARCHAR NOT NULL,"
                + " source_version integer DEFAULT 1,"
                + " payload JSONB NOT NULL"
                + ")",
            "DROP INDEX IF EXISTS idx_local_id",
            "ALTER TABLE " + globalRecordTable + " ADD COLUMN IF NOT EXISTS"
                + " source_version integer DEFAULT 1",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_local_source ON " + globalRecordTable
                + " (local_id, source_id, source_version)",
            "CREATE INDEX IF NOT EXISTS idx_source ON " + globalRecordTable
                + " (source_id, source_version)",
            CREATE_IF_NO_EXISTS + matchKeyConfigTable
                + "(id VARCHAR NOT NULL PRIMARY KEY,"
                + " matcher VARCHAR, "
                + " method VARCHAR, "
                + " update VARCHAR, "
                + " params JSONB)",
            "ALTER TABLE " + matchKeyConfigTable + " ADD COLUMN IF NOT EXISTS"
                + " matcher VARCHAR",
            CREATE_IF_NO_EXISTS + clusterMetaTable
                + "(cluster_id uuid NOT NULL PRIMARY KEY,"
                + " match_key_config_id VARCHAR NOT NULL,"
                + " datestamp TIMESTAMP,"
                + " FOREIGN KEY(match_key_config_id) REFERENCES " + matchKeyConfigTable
                + " ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS cluster_meta_datestamp_idx ON "
                + clusterMetaTable + "(datestamp)",
            CREATE_IF_NO_EXISTS + clusterRecordTable
                + "(record_id uuid NOT NULL,"
                + " match_key_config_id VARCHAR NOT NULL,"
                + " cluster_id uuid NOT NULL,"
                + " FOREIGN KEY(match_key_config_id) REFERENCES " + matchKeyConfigTable
                + " ON DELETE CASCADE,"
                + " FOREIGN KEY(record_id) REFERENCES " + globalRecordTable + " ON DELETE CASCADE)",
            "CREATE UNIQUE INDEX IF NOT EXISTS cluster_record_record_matchkey_idx ON "
                + clusterRecordTable + "(record_id, match_key_config_id)",
            "CREATE INDEX IF NOT EXISTS cluster_record_cluster_idx ON "
                + clusterRecordTable + "(cluster_id)",
            CREATE_IF_NO_EXISTS + clusterValueTable
                + "(cluster_id uuid NOT NULL,"
                + " match_key_config_id VARCHAR NOT NULL,"
                + " match_value VARCHAR NOT NULL,"
                + " FOREIGN KEY(match_key_config_id) REFERENCES " + matchKeyConfigTable
                + " ON DELETE CASCADE)",
            "CREATE UNIQUE INDEX IF NOT EXISTS cluster_value_value_idx ON "
                + clusterValueTable + "(match_key_config_id, match_value)",
            "CREATE INDEX IF NOT EXISTS cluster_value_cluster_idx ON "
                + clusterValueTable + "(cluster_id)",
            CREATE_IF_NO_EXISTS + moduleTable
                + "(id VARCHAR NOT NULL PRIMARY KEY,"
                + " type VARCHAR,"
                + " url VARCHAR, "
                + " function VARCHAR,"
                + " script VARCHAR,"
                + " hash VARCHAR NOT NULL)",
            "ALTER TABLE " + moduleTable + " ADD COLUMN IF NOT EXISTS"
                + " type VARCHAR",
            "ALTER TABLE " + moduleTable + " ADD COLUMN IF NOT EXISTS"
                + " script VARCHAR",
            CREATE_IF_NO_EXISTS + oaiConfigTable
                + "(id VARCHAR NOT NULL PRIMARY KEY,"
                + " config JSONB NOT NULL)",
            CREATE_IF_NO_EXISTS + oaiPmhClientTable
                + "(id VARCHAR NOT NULL PRIMARY KEY,"
                + " config JSONB, job JSONB, stop BOOLEAN, owner UUID)"
        )
    ).mapEmpty();
  }

  private Future<Boolean> upsertGlobalRecord(String localIdentifier, SourceId sourceId,
      int sourceVersion, JsonObject payload, List<MatcherResult> matcherResults,
      IngestMetrics ingestMetrics) {
    return upsertGlobalRecord(matcherResults.size(), localIdentifier, sourceId,
        sourceVersion, payload, matcherResults, ingestMetrics);
  }

  private Future<Boolean> upsertGlobalRecord(int retryCount, String localIdentifier,
      SourceId sourceId, int sourceVersion, JsonObject payload, List<MatcherResult> matcherResults,
      IngestMetrics ingestMetrics) {
    return pool.withTransaction(conn ->
            upsertGlobalRecord(conn, localIdentifier, sourceId, sourceVersion,
                payload, matcherResults, ingestMetrics))
        // addValuesToCluster may fail if for same new match key for parallel operations
        // we recover just once for that. 2nd will find the new value for the one that
        // succeeded.
        .recover(e -> {
          if (retryCount == 0) {
            return Future.failedFuture(e);
          }
          return upsertGlobalRecord(retryCount - 1, localIdentifier, sourceId, sourceVersion,
              payload, matcherResults, ingestMetrics);
        });
  }

  Future<Boolean> upsertGlobalRecord(SqlConnection conn, String localIdentifier,
      SourceId sourceId, int sourceVersion, JsonObject payload, List<MatcherResult> matcherResults,
      IngestMetrics ingestMetrics) {
    UUID startId = UUID.randomUUID();
    return conn.preparedQuery(
            "INSERT INTO " + globalRecordTable
                + " (id, local_id, source_id, source_version, payload)"
                + " VALUES ($1, $2, $3, $4, $5)"
                + " ON CONFLICT (local_id, source_id, source_version) DO UPDATE "
                + " SET payload = $5"
                + " RETURNING id"
        )
        .execute(Tuple.of(startId, localIdentifier, sourceId.toString(), sourceVersion, payload))
        .map(rowSet -> rowSet.iterator().next().getUUID("id"))
        .compose(id -> updateMatchKeyValues(conn, id, matcherResults, ingestMetrics)
            .map(x -> id.equals(startId)));
  }

  Future<Void> deleteGlobalRecord(String localIdentifier, SourceId sourceId, int sourceVersion) {
    String q = "UPDATE " + clusterMetaTable + " AS m"
        + " SET datestamp = $4"
        + " FROM " + globalRecordTable + ", " + clusterRecordTable + " AS r"
        + " WHERE m.cluster_id = r.cluster_id AND r.record_id = id"
        + " AND local_id = $1 AND source_id = $2 and source_version = $3";
    return pool.withTransaction(conn ->
        conn.preparedQuery(q)
          .execute(Tuple.of(localIdentifier, sourceId.toString(), sourceVersion,
              LocalDateTime.now(ZoneOffset.UTC)))
          .compose(x -> conn.preparedQuery("DELETE FROM " + globalRecordTable
                  + " WHERE local_id = $1 AND source_id = $2 and source_version = $3")
          .execute(Tuple.of(localIdentifier, sourceId.toString(), sourceVersion))
          .mapEmpty()));
  }

  /**
   * Insert/update/delete global record.
   * @param vertx Vert.x handle
   * @param sourceId source identifier
   * @param sourceVersion source version
   * @param globalRecord global record JSON object
   * @param ingestMatchers match key configurations in use
   * @param ingestMetrics ingest metrics collector
   * @return async result with TRUE=inserted, FALSE=updated, null=deleted
   */
  Future<Boolean> ingestGlobalRecord(Vertx vertx,
      SourceId sourceId, int sourceVersion, JsonObject globalRecord,
      List<IngestMatcher> ingestMatchers, IngestMetrics ingestMetrics) {

    long startTime = System.nanoTime();
    return ingestGlobalRecord2(vertx, sourceId, sourceVersion, globalRecord,
        ingestMatchers, ingestMetrics)
        .onComplete(x -> ingestMetrics.recordStoring(
            System.nanoTime() - startTime, TimeUnit.NANOSECONDS)
        );
  }

  private Future<Boolean> ingestGlobalRecord2(Vertx vertx,
      SourceId sourceId, int sourceVersion, JsonObject globalRecord,
      List<IngestMatcher> ingestMatchers, IngestMetrics ingestMetrics) {

    final String localIdentifier = globalRecord.getString("localId");
    if (localIdentifier == null) {
      ingestMetrics.incrementRecordsIgnored();
      return Future.failedFuture("localId required");
    }
    if (Boolean.TRUE.equals(globalRecord.getBoolean("delete"))) {
      return deleteGlobalRecord(localIdentifier, sourceId, sourceVersion)
        .map(x -> {
          ingestMetrics.incrementRecordsDeleted();
          return null;
        });
    }
    final JsonObject payload = globalRecord.getJsonObject("payload");
    if (payload == null) {
      ingestMetrics.incrementRecordsIgnored();
      return Future.failedFuture("payload required");
    }
    if (sourceId == null) {
      ingestMetrics.incrementRecordsIgnored();
      return Future.failedFuture("sourceId required");
    }
    List<Future<MatcherResult>> futures = new ArrayList<>();
    for (IngestMatcher ingestMatcher : ingestMatchers) {
      futures.add(runMatcher(ingestMatcher, ingestMetrics, payload));
    }
    return Future.all(futures).compose(cf -> {
      List<MatcherResult> results = new ArrayList<>(cf.size());
      for (int i = 0; i < cf.size(); i++) {
        results.add((MatcherResult) cf.resultAt(i));
      }
      return upsertGlobalRecord(localIdentifier, sourceId, sourceVersion,
          payload, results, ingestMetrics)
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
      JsonObject payload) {
    MatcherResult result = new MatcherResult();
    result.matchKeyId = ingestMatcher.matchKeyId;
    result.keys = new HashSet<>();
    var startTime = System.nanoTime();
    if (ingestMatcher.moduleExecutable != null) {
      return ingestMatcher.moduleExecutable.executeAsCollection(payload)
          .map(values -> {
            values.forEach(k -> result.keys.add(k.length() > MATCHVALUE_MAX_LENGTH
                ? k.substring(0, MATCHVALUE_MAX_LENGTH) : k));
            ingestMetrics.recordMatcher(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
            return result;
          });
    }
    if (ingestMatcher.matchKeyMethod != null) {
      HashSet<String> values = new HashSet<>();
      ingestMatcher.matchKeyMethod.getKeys(payload, values);
      values.forEach(k -> result.keys.add(k.length() > MATCHVALUE_MAX_LENGTH
          ? k.substring(0, MATCHVALUE_MAX_LENGTH) : k));

      ingestMetrics.recordMatcher(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
    }
    return Future.succeededFuture(result);
  }

  Future<Void> updateMatchKeyValues(SqlConnection conn, UUID globalId,
      List<MatcherResult> matcherResults, IngestMetrics ingestMetrics) {
    List<Future<Void>> futures = new ArrayList<>();
    for (MatcherResult matcherResult : matcherResults) {
      futures.add(updateClusterForRecord(conn, globalId, matcherResult));
    }
    return Future.all(futures).mapEmpty();
  }

  Future<IngestMatcher> createIngestMatcher(JsonObject matchKeyConfig, Vertx vertx) {
    IngestMatcher ingestMatcher = new IngestMatcher();

    ingestMatcher.matchKeyId = matchKeyConfig.getString("id");
    String matcherProp = matchKeyConfig.getString("matcher");
    if (matcherProp != null) {
      ModuleInvocation invocation = new ModuleInvocation(matcherProp);
      return selectCodeModuleEntity(invocation.getModuleName())
          .compose(entity -> {
            if (entity == null) {
              return Future.failedFuture(
                  "Module '" + invocation.getModuleName()
                      + "' does not exist for '" + invocation + "'");
            }
            return ModuleCache.getInstance().lookup(vertx, tenant, entity)
              .compose(module -> {
                ingestMatcher.moduleExecutable = new ModuleExecutable(module, invocation);
                return Future.succeededFuture(ingestMatcher);
              });
          });
    }
    String methodName = matchKeyConfig.getString("method");
    if (methodName != null) {
      JsonObject params = matchKeyConfig.getJsonObject("params");
      return MatchKeyMethod.get(vertx, tenant, ingestMatcher.matchKeyId, methodName, params)
          .compose(matchKeyMethod -> {
            ingestMatcher.matchKeyMethod = matchKeyMethod;
            return Future.succeededFuture(ingestMatcher);
          });
    }
    return Future.failedFuture("match key config must include 'method' or 'matcher'");
  }

  Future<List<IngestMatcher>> createIngestMatchers(JsonArray matchKeyConfigs, Vertx vertx) {
    List<Future<IngestMatcher>> futures = new ArrayList<>();
    for (int i = 0; i < matchKeyConfigs.size(); i++) {
      JsonObject matchKeyConfig = matchKeyConfigs.getJsonObject(i);
      String update = matchKeyConfig.getString("update");
      if ("manual".equals(update)) {
        continue;
      }
      futures.add(createIngestMatcher(matchKeyConfig, vertx));
    }
    return Future.all(futures).map(composite -> {
      List<IngestMatcher> ingestMatchers = new ArrayList<>();
      for (int i = 0; i < composite.size(); i++) {
        ingestMatchers.add(composite.resultAt(i));
      }
      return ingestMatchers;
    });
  }

  Future<List<IngestMatcher>> availableIngestMatchers(Vertx vertx) {
    return getAvailableMatchConfigs()
       .compose(matchKeyConfigs -> createIngestMatchers(matchKeyConfigs, vertx));
  }

  Future<Set<UUID>> updateClusterValues(SqlConnection conn, UUID newClusterId,
      MatcherResult matcherResult) {
    Set<UUID> clustersFound = new HashSet<>();
    if (matcherResult.keys.isEmpty()) {
      return Future.succeededFuture(clustersFound);
    }
    StringBuilder q = new StringBuilder("SELECT cluster_id, match_value FROM " + clusterValueTable
        + " WHERE match_key_config_id = $1 AND (");
    List<Object> tupleList = new ArrayList<>();
    tupleList.add(matcherResult.matchKeyId);
    int no = 2;
    for (String key : matcherResult.keys) {
      if (no > 2) {
        q.append(" OR ");
      }
      q.append("match_value = $" + no++);
      tupleList.add(key);
    }
    q.append(")");
    Set<String> foundKeys = new HashSet<>();
    return conn.preparedQuery(q.toString())
        .execute(Tuple.from(tupleList))
        .map(rowSet -> {
          rowSet.forEach(row -> {
            foundKeys.add(row.getString("match_value"));
            clustersFound.add(row.getUUID("cluster_id"));
          });
          if (clustersFound.isEmpty()) {
            return newClusterId;
          } else {
            return clustersFound.iterator().next();
          }
        })
        .compose(clusterId ->
            addValuesToCluster(conn, clusterId, matcherResult, foundKeys)
        )
        .map(clustersFound);
  }

  Future<Integer> touchClusters(PgCqlQuery query) {
    String where = query.getWhereClause();
    if (where == null
        || !where.contains("global_records.source_id")
        || !where.contains("cluster_meta.match_key_config_id")) {
      return Future.failedFuture(
        "query too broad, must at least contain 'matchkeyId' and 'sourceId'");
    }
    String q = "UPDATE " + clusterMetaTable
        + " SET datestamp = $1"
        + " FROM " + globalRecordTable + ", " + clusterRecordTable
        + " WHERE cluster_meta.cluster_id = cluster_records.cluster_id"
        + " AND cluster_records.record_id = global_records.id"
        + " AND " + where;
    return pool.preparedQuery(q)
        .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC)))
        .map(RowSet<Row>::rowCount);
  }

  Future<Void> updateClusterForRecord(SqlConnection conn, UUID globalId,
      MatcherResult matcherResult) {
    UUID newClusterId = UUID.randomUUID();
    return updateClusterValues(conn, newClusterId, matcherResult)
        .compose(clustersFound -> {
          Iterator<UUID> iterator = clustersFound.iterator();
          if (!iterator.hasNext()) {
            return createMetaEntry(conn, newClusterId, matcherResult.matchKeyId);
          }
          return updateMetaEntries(conn, clustersFound).compose(c -> {
            UUID clusterId = iterator.next();
            if (!iterator.hasNext()) {
              return Future.succeededFuture(clusterId); // exactly one already
            }
            // multiple clusters: merge remaining with this one
            return mergeClusters(conn, clusterId, iterator).map(clusterId);
          });
        })
        .compose(clusterId ->
            conn.preparedQuery("INSERT INTO " + clusterRecordTable
                    + " (record_id, match_key_config_id, cluster_id) VALUES ($1, $2, $3)"
                    + " ON CONFLICT (record_id, match_key_config_id)"
                    + " DO UPDATE SET record_id = $1, match_key_config_id = $2, cluster_id = $3")
                .execute(Tuple.of(globalId, matcherResult.matchKeyId, clusterId))
        )
        .mapEmpty();
  }

  Future<Void> addValuesToCluster(SqlConnection conn, UUID clusterId, MatcherResult matcherResult,
      Set<String> foundKeys) {

    StringBuilder q = new StringBuilder("INSERT INTO " + clusterValueTable
        + " (cluster_id, match_key_config_id, match_value) VALUES");

    List<Object> tupleList = new ArrayList<>();
    tupleList.add(clusterId);
    tupleList.add(matcherResult.matchKeyId);
    int no = 3;
    for (String key: matcherResult.keys) {
      if (!foundKeys.contains(key)) {
        if (no > 3) {
          q.append(",");
        }
        q.append(" ($1, $2, $" + no + ")");
        tupleList.add(key);
        no++;
      }
    }
    if (no == 3) {
      return Future.succeededFuture();
    }
    return conn.preparedQuery(q.toString())
        .execute(Tuple.from(tupleList))
        .mapEmpty();
  }

  Future<UUID> createMetaEntry(SqlConnection conn, UUID clusterId, String matchKeyConfigId) {
    return conn.preparedQuery("INSERT INTO " + clusterMetaTable
            + " (cluster_id, datestamp, match_key_config_id) VALUES ($1, $2, $3)")
        .execute(Tuple.of(clusterId, LocalDateTime.now(ZoneOffset.UTC), matchKeyConfigId))
        .map(clusterId);
  }

  Future<Void> updateMetaEntries(SqlConnection conn, Set<UUID> clusters) {
    Iterator<UUID> iterator = clusters.iterator();
    StringBuilder setClause = new StringBuilder("UPDATE " + clusterMetaTable
        + " SET datestamp = $1 WHERE ");
    List<Object> tupleList = new ArrayList<>();
    tupleList.add(LocalDateTime.now(ZoneOffset.UTC));
    for (int no = 2; iterator.hasNext(); no++) {
      tupleList.add(iterator.next());
      if (no > 2) {
        setClause.append(" OR ");
      }
      setClause.append("cluster_id = $");
      setClause.append(no);
    }
    return conn.preparedQuery(setClause.toString())
        .execute(Tuple.from(tupleList))
        .mapEmpty();
  }

  Future<Void> mergeClusters(SqlConnection conn, UUID clusterId, Iterator<UUID> iterator) {
    StringBuilder setClause = new StringBuilder(" SET cluster_id = $1 WHERE ");
    List<UUID> tupleList = new ArrayList<>();
    tupleList.add(clusterId); // $1
    for (int no = 2; iterator.hasNext(); no++) {
      tupleList.add(iterator.next());
      if (no > 2) {
        setClause.append(" OR ");
      }
      setClause.append("cluster_id = $");
      setClause.append(no);
    }
    return conn.preparedQuery("UPDATE " + clusterValueTable + setClause)
        .execute(Tuple.from(tupleList))
        .compose(x -> conn.preparedQuery("UPDATE " + clusterRecordTable + setClause)
            .execute(Tuple.from(tupleList)))
        .mapEmpty();
  }

  /**
   * Update/insert set of global records.
   * @param vertx Vert.x handle
   * @param request ingest record request
   * @return async result
   */
  public Future<Void> updateGlobalRecords(Vertx vertx, LargeJsonReadStream request) {
    return availableIngestMatchers(vertx)
      .compose(ingestMatchers -> {
        return new ReadStreamConsumer<JsonObject, Void>()
          .consume(request, r -> {
            SourceId sourceId = new SourceId(request.topLevelObject().getString("sourceId"));
            IngestMetrics ingestMetrics = IngestMetrics.create().withSource(sourceId);
            Integer sourceVersion = request.topLevelObject().getInteger("sourceVersion", 1);
            return ingestGlobalRecord(vertx, sourceId,
              sourceVersion, r, ingestMatchers, ingestMetrics)
          .mapEmpty();
          });
      });
  }

  /**
   * Get available match key configurations.
   * @return async result with array of configurations
   */
  public Future<JsonArray> getAvailableMatchConfigs() {
    return pool.query("SELECT * FROM " + matchKeyConfigTable)
        .execute()
        .map(res -> {
          JsonArray matchConfigs = new JsonArray();
          res.forEach(row ->
              matchConfigs.add(new JsonObject()
                  .put("id", row.getString("id"))
                  .put("matcher", row.getString("matcher"))
                  .put("method", row.getString("method"))
                  .put("params", row.getJsonObject("params"))
                  .put("update", row.getString("update"))
              ));
          return matchConfigs;
        });
  }

  /**
   * Delete global records and update timestamp.
   * @param sqlWhere SQL WHERE clause
   * @return async result
   */
  public Future<Void> deleteGlobalRecords(String sqlWhere) {
    String q = "UPDATE " + clusterMetaTable + " AS m"
        + " SET datestamp = $1"
        + " FROM " + globalRecordTable + ", " + clusterRecordTable + " AS r"
        + " WHERE m.cluster_id = r.cluster_id AND r.record_id = id";
    if (sqlWhere != null) {
      q = q + " AND " + sqlWhere;
      if (sqlWhere.contains("source_version=")) {
        // if limit by source version, then update timestamp only if this cluster only
        // contains the source_version and not others. The datestamp for cluster was
        // already updated on update during ingest.
        q = q + " AND NOT EXISTS (SELECT 1 FROM "
            + globalRecordTable + ", " + clusterRecordTable + " AS r"
            + " WHERE m.cluster_id = r.cluster_id AND r.record_id = id"
            + " AND " + sqlWhere.replace("source_version=", "source_version!=") + ")";
      }
    }
    return pool.preparedQuery(q)
        .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC)))
        .compose(rowSet -> {
          log.info("Number of meta records updated = {}", rowSet.rowCount());
          String from = globalRecordTable;
          if (sqlWhere != null) {
            from = from + " WHERE " + sqlWhere;
          }
          return pool.query("DELETE FROM " + from).execute();
        })
        .mapEmpty();
  }

  /**
   * Get global records.
   * @param ctx routing context
   * @param sqlWhere SQL WHERE clause
   * @param sqlOrderBy the SQL ORDER BY clause
   * @return async result
   */
  public Future<Void> getGlobalRecords(RoutingContext ctx, String sqlWhere, String sqlOrderBy) {
    String from = globalRecordTable;
    if (sqlWhere != null) {
      from = from + " WHERE " + sqlWhere;
    }
    return streamResult(ctx, null, from, sqlOrderBy, "items",
        row -> Future.succeededFuture(ClusterBuilder.encodeRecord(row)));
  }

  /**
   * Get cluster by cluster identifier.
   * @param clusterId cluster identifier
   * @return cluster object; null if not found
   */
  public Future<JsonObject> getClusterById(UUID clusterId) {
    return pool.withConnection(connection -> getClusterById(connection, clusterId));
  }

  Future<JsonObject> getClusterById(SqlConnection connection, UUID clusterId) {
    // get all records part of cluster and join with cluster_meta to get datestamp
    return connection.preparedQuery("SELECT * FROM " + globalRecordTable
            + " LEFT JOIN " + clusterRecordTable + " ON id = record_id"
            + " LEFT JOIN " + clusterMetaTable + " ON "
            + clusterMetaTable + ".cluster_id = " + clusterRecordTable + ".cluster_id"
            + " WHERE " + clusterRecordTable + ".cluster_id = $1")
        .execute(Tuple.of(clusterId))
        .map(rowSet -> {
          ClusterBuilder cb = new ClusterBuilder(clusterId);
          RowIterator<Row> iterator = rowSet.iterator();
          if (iterator.hasNext()) {
            Row row = iterator.next();
            cb.datestamp(row.getLocalDateTime("datestamp"));
          }
          cb.records(rowSet);
          return cb;
        })
        .compose(cb -> connection.preparedQuery("SELECT * FROM " + clusterValueTable
              + " WHERE cluster_id = $1")
            .execute(Tuple.of(clusterId))
            .map(cb::matchValues))
        .map(ClusterBuilder::build);
  }

  /**
   * return all clusters as streaming result.
   * @param ctx routing context
   * @param matchKeyId match ke config to use
   * @return async result
   */
  public Future<Void> getClusters(RoutingContext ctx, String matchKeyId,
      String sqlWhere, String sqlOrderBy) {
    String joinGlobal = "";
    if (sqlWhere != null && sqlWhere.contains("global_records.")) {
      joinGlobal = " LEFT JOIN " + globalRecordTable + " ON "
          + clusterRecordTable + ".record_id = " + globalRecordTable + ".id";
    }
    String joinClusterValue = "";
    if (sqlWhere != null && sqlWhere.contains("cluster_values.match_value")) {
      joinClusterValue = " LEFT JOIN " + clusterValueTable + " ON "
          + clusterValueTable + ".cluster_id = " + clusterRecordTable + ".cluster_id";
    }
    String from = clusterRecordTable
        + joinClusterValue
        + joinGlobal
        + " WHERE " + clusterRecordTable + ".match_key_config_id = $1";
    if (sqlWhere != null) {
      from = from + " AND (" + sqlWhere + ")";
    }
    return streamResult(ctx, clusterRecordTable + ".cluster_id", Tuple.of(matchKeyId),
        from, sqlOrderBy, "items",
        row -> getClusterById(row.getUUID("cluster_id")));
  }

  /**
   * Get global record given global identifier.
   * @param id global identifier
   * @return global record response as JSON object
   */
  public Future<JsonObject> getGlobalRecord(String id) {
    return pool.preparedQuery(
            "SELECT * FROM " + globalRecordTable + " WHERE id = $1")
        .execute(Tuple.of(id))
        .map(res -> {
          RowIterator<Row> iterator = res.iterator();
          if (!iterator.hasNext()) {
            return null;
          }
          return ClusterBuilder.encodeRecord(iterator.next());
        });
  }

  /**
   * Insert match key config into storage.
   * @param id match key id (user specified)
   * @param method match key method
   * @param params configuration
   * @param update strategy
   * @return async result
   */
  public Future<Void> insertMatchKeyConfig(String id, String matcher, String method,
      JsonObject params, String update) {

    return pool.preparedQuery(
        "INSERT INTO " + matchKeyConfigTable + " (id, matcher, method, params, update)"
            + " VALUES ($1, $2, $3, $4, $5)")
        .execute(Tuple.of(id, matcher, method, params, update))
        .mapEmpty();
  }

  /**
   * Update match key config into storage.
   * @param id match key id (user specified)
   * @param method match key method
   * @param params configuration
   * @param update strategy
   * @return async result with TRUE if updated; FALSE if not found
   */
  public Future<Boolean> updateMatchKeyConfig(String id, String matcher, String method,
      JsonObject params, String update) {

    return pool.preparedQuery(
            "UPDATE " + matchKeyConfigTable
                + " SET matcher = $2, method = $3, params = $4, update = $5 WHERE id = $1")
        .execute(Tuple.of(id, matcher, method, params, update))
        .map(res -> res.rowCount() > 0);
  }

  /**
   * Select match key from storage.
   * @param id match key id; null takes any first config
   * @return JSON object if found; null if not found
   */
  public Future<JsonObject> selectMatchKeyConfig(String id) {
    String q = "SELECT * FROM " + matchKeyConfigTable;
    List<String> tupleList = new ArrayList<>();
    if (id != null) {
      q = q + " WHERE id = $1";
      tupleList.add(id);
    }
    return pool.preparedQuery(q)
        .execute(Tuple.from(tupleList))
        .map(res -> {
          RowIterator<Row> iterator = res.iterator();
          if (!iterator.hasNext()) {
            return null;
          }
          Row row = iterator.next();
          return new JsonObject()
              .put("id", row.getString("id"))
              .put("matcher", row.getString("matcher"))
              .put("method", row.getString("method"))
              .put("params", row.getJsonObject("params"))
              .put("update", row.getString("update"));
        });
  }

  /**
   * Delete match key.
   * @param id match key identifier
   * @return TRUE if deleted; FALSE if not found
   */
  public Future<Boolean> deleteMatchKeyConfig(String id) {
    return pool.withConnection(connection ->
        connection.preparedQuery(
                "DELETE FROM " + matchKeyConfigTable + " WHERE id = $1")
            .execute(Tuple.of(id))
            .map(res -> res.rowCount() > 0));
  }

  /**
   * Get match keys.
   * @param ctx routing context
   * @param sqlWhere the SQL WHERE clause
   * @param sqlOrderBy the SQL ORDER BY clause
   * @return async result
   */
  public Future<Void> getMatchKeyConfigs(RoutingContext ctx, String sqlWhere, String sqlOrderBy) {
    String from = matchKeyConfigTable;
    if (sqlWhere != null) {
      from = from + " WHERE " + sqlWhere;
    }
    return streamResult(ctx, null, from, sqlOrderBy, "matchKeys",
        row -> Future.succeededFuture(new JsonObject()
            .put("id", row.getString("id"))
            .put("matcher", row.getString("matcher"))
            .put("method", row.getString("method"))
            .put("params", row.getJsonObject("params"))
            .put("update", row.getString("update"))
        ));
  }

  Future<JsonObject> recalculateMatchKeyValueTable(SqlConnection connection,
      IngestMatcher ingestMatcher) {
    String query = "SELECT * FROM " + globalRecordTable;
    AtomicInteger totalRecords = new AtomicInteger();
    IngestMetrics ingestMetrics = new IngestMetricsNop();
    return connection.prepare(query).compose(pq ->
        connection.begin().compose(tx -> {
          RowStream<Row> stream = pq.createStream(sqlStreamFetchSize);
          Promise<JsonObject> promise = Promise.promise();
          stream.handler(row -> {
            stream.pause();
            totalRecords.incrementAndGet();
            UUID globalId = row.getUUID("id");
            JsonObject payload = row.getJsonObject("payload");
            runMatcher(ingestMatcher, ingestMetrics, payload)
                .compose(matcherResult ->
                    updateClusterForRecord(connection, globalId, matcherResult))
                .onFailure(e -> log.error(e.getMessage(), e))
                .onComplete(e -> stream.resume());
          });
          stream.endHandler(end -> {
            tx.commit();
            promise.complete(new JsonObject().put("totalRecords", totalRecords.get()));
          });
          stream.exceptionHandler(e -> {
            log.error(e.getMessage(), e);
            tx.commit();
            promise.fail(e);
          });
          return promise.future();
        })
    );
  }

  /**
   * Initialize match key (populate clusters).
   * @param vertx Vert.x handle
   * @param id match key id (user specified)
   * @return statistics
   */
  public Future<JsonObject> initializeMatchKey(Vertx vertx, String id) {
    return pool.withConnection(connection ->
        connection.preparedQuery(
                "SELECT * FROM " + matchKeyConfigTable + " WHERE id = $1")
            .execute(Tuple.of(id))
            .compose(res -> {
              RowIterator<Row> iterator = res.iterator();
              if (!iterator.hasNext()) {
                return Future.succeededFuture();
              }
              Row row = iterator.next();
              String matcherProp = row.getString("matcher");
              JsonObject matchKeyConfig = new JsonObject();
              matchKeyConfig.put("id", id);
              matchKeyConfig.put("matcher", matcherProp);
              matchKeyConfig.put("method", row.getString("method"));
              matchKeyConfig.put("params", row.getJsonObject("params"));
              return createIngestMatcher(matchKeyConfig, vertx)
                .compose(matcher -> recalculateMatchKeyValueTable(connection, matcher));
            })
    );
  }

  class StatsTrack {
    UUID clusterId;
    int clustersTotal;
    final Set<String> values = new HashSet<>();
    final Set<UUID> recordIds = new HashSet<>();
    final Map<Integer, Integer> matchValuesPerCluster = new HashMap<>();
    final Map<Integer, Integer> recordsPerCluster = new HashMap<>();
    final Map<Integer, JsonArray> recordsPerClusterSample = new HashMap<>();

    void newCluster() {
      if (clusterId != null) {
        matchValuesPerCluster.merge(values.size(), 1, (x, y) -> x + y);
        int size = recordIds.size();
        recordsPerCluster.merge(size, 1, (x, y) -> x + y);
        JsonArray samples = recordsPerClusterSample.computeIfAbsent(size,
            x -> new JsonArray());
        if (samples.size() < 3) {
          samples.add(clusterId.toString());
        }
      }
    }
  }

  /**
   * get match key statistics.
   * @param id match key id (user specified)
   * @return statistics
   */
  public Future<JsonObject> statsMatchKey(String id) {
    String qry = "SELECT * FROM "
        + clusterRecordTable + " LEFT JOIN " + clusterValueTable + " ON "
        + clusterValueTable + ".cluster_id = " + clusterRecordTable + ".cluster_id"
        + " WHERE " + clusterRecordTable + ".match_key_config_id = $1"
        + " ORDER BY " + clusterValueTable + ".cluster_id";

    Tuple tuple = Tuple.of(id);
    return pool.withConnection(connection ->
        connection.prepare(qry).compose(pq ->
            connection.begin().compose(tx -> {
              StatsTrack st = new StatsTrack();
              Promise<JsonObject> promise = Promise.promise();
              RowStream<Row> stream = pq.createStream(sqlStreamFetchSize, tuple);
              stream.handler(row -> {
                UUID clusterId = row.getUUID("cluster_id");
                if (!clusterId.equals(st.clusterId)) {
                  st.newCluster();
                  st.clustersTotal++;
                  st.values.clear();
                  st.recordIds.clear();
                  st.clusterId = clusterId;
                }
                String v = row.getString("match_value");
                if (v != null) {
                  st.values.add(v);
                }
                st.recordIds.add(row.getUUID("record_id"));
                log.debug("row = {}", row::deepToString);
              });
              stream.endHandler(end -> {
                st.newCluster();
                JsonObject matchValuesPer = new JsonObject();
                st.matchValuesPerCluster.forEach((k, v) ->
                    matchValuesPer.put(Integer.toString(k), v));
                JsonObject recordsPer = new JsonObject();
                AtomicInteger totalRecs = new AtomicInteger();
                st.recordsPerCluster.forEach((k, v) -> {
                  recordsPer.put(Integer.toString(k), v);
                  totalRecs.addAndGet(k * v);
                });
                JsonObject clusterSamplePer = new JsonObject();
                st.recordsPerClusterSample.forEach((k, v) ->
                    clusterSamplePer.put(Integer.toString(k), v));
                promise.complete(new JsonObject()
                    .put("recordsTotal", totalRecs.get())
                    .put("clustersTotal", st.clustersTotal)
                    .put("matchValuesPerCluster", matchValuesPer)
                    .put("recordsPerCluster", recordsPer)
                    .put("recordsPerClusterSample", clusterSamplePer)
                );
              });
              return promise.future();
            })
        )
    );
  }

  /**
   * Insert code module config into storage.
   * @param module code module entity
   * @return async result
   */
  public Future<Void> insertCodeModuleEntity(CodeModuleEntity module) {

    return pool.preparedQuery(
            "INSERT INTO " + moduleTable + " (id, type, url, function, script, hash)"
                + " VALUES ($1, $2, $3, $4, $5, $6)")
        .execute(module.asTuple())
        .mapEmpty();
  }

  /**
   * Update code module config in storage.
   * @param module code module entity
   * @return async result with TRUE if updated; FALSE if not found
   */
  public Future<Boolean> updateCodeModuleEntity(CodeModuleEntity module) {

    return pool.preparedQuery(
            "UPDATE " + moduleTable
                + " SET type = $2, url = $3, function = $4, script = $5, hash = $6 WHERE id = $1")
        .execute(module.asTuple())
        .map(res -> res.rowCount() > 0);
  }

  /**
   * Select code module entity from storage.
   * @param id code module id; null takes any first config
   * @return code module entity if found; null if not found
   */
  public Future<CodeModuleEntity> selectCodeModuleEntity(String id) {
    return pool.withConnection(conn -> selectCodeModuleEntity(conn, id));
  }

  /**
   * Select code module entity from storage.
   * @param id code module id; null takes any first config
   * @return code module entity if found; null if not found
   */
  public Future<CodeModuleEntity> selectCodeModuleEntity(SqlConnection conn, String id) {
    String sql = "SELECT * FROM " + moduleTable;
    List<String> tupleList = new ArrayList<>();
    if (id != null) {
      sql = sql + " WHERE id = $1";
      tupleList.add(id);
    }
    return conn.preparedQuery(sql)
        .execute(Tuple.from(tupleList))
        .map(res -> {
          RowIterator<Row> iterator = res.iterator();
          if (!iterator.hasNext()) {
            return null;
          }
          Row row = iterator.next();
          return new CodeModuleEntity.CodeModuleBuilder(row).build();
        });
  }

  /**
   * Delete code module entity.
   * @param id code module entity id
   * @return TRUE if deleted; FALSE if not found
   */
  public Future<Boolean> deleteCodeModuleEntity(String id) {
    return pool.withConnection(connection ->
        connection.preparedQuery(
                "DELETE FROM " + moduleTable + " WHERE id = $1")
            .execute(Tuple.of(id))
            .map(res -> res.rowCount() > 0));
  }

  /**
   * Select code module entities keys.
   * @param ctx routing context
   * @param sqlWhere the SQL WHERE clause
   * @param sqlOrderBy the SQL ORDER BY clause
   * @return async result
   */
  public Future<Void> selectCodeModuleEntities(RoutingContext ctx,
      String sqlWhere, String sqlOrderBy) {
    String from = moduleTable;
    if (sqlWhere != null) {
      from = from + " WHERE " + sqlWhere;
    }
    return streamResult(ctx, null, from, sqlOrderBy, "modules",
        row -> Future.succeededFuture(CodeModuleEntity.CodeModuleBuilder.asJson(row)));
  }

  //end modules
  //start oai config

  /**
   * Update OAI config.
   * @param config OAI config
   * @return async result with TRUE if updated; FALSE if not found
   */
  public Future<Boolean> updateOaiConfig(JsonObject config) {

    return pool.preparedQuery(
      "INSERT INTO " + oaiConfigTable + " (id, config)"
          + " VALUES ($1, $2) ON CONFLICT(id) DO UPDATE SET config = $2")
      .execute(Tuple.of("1", config))
      .mapEmpty();
  }

  /**
   * Select OAI config.
   * @return OAI config
   */
  public Future<JsonObject> selectOaiConfig() {
    String sql = "SELECT * FROM " + oaiConfigTable + " WHERE id = '1'";

    return pool.preparedQuery(sql)
        .execute()
        .map(res -> {
          RowIterator<Row> iterator = res.iterator();
          if (!iterator.hasNext()) {
            return null;
          }
          Row row = iterator.next();
          return row.getJsonObject("config");
        });
  }

  /**
   * Delete OAI config.
   * @return async void result
   */
  public Future<Void> deleteOaiConfig() {
    return pool.preparedQuery("DELETE FROM " + oaiConfigTable + " WHERE id = $1")
        .execute(Tuple.of("1")).mapEmpty();
  }

  // end oai config

  private static JsonObject copyWithoutNulls(JsonObject obj) {
    JsonObject n = new JsonObject();
    obj.getMap().forEach((key, value) -> {
      if (value != null) {
        n.put(key, value);
      }
    });
    return n;
  }

  static void resultFooter(RoutingContext ctx, RowSet<Row> rowSet, List<String[]> facets,
      String diagnostic) {

    JsonObject resultInfo = new JsonObject();
    JsonArray facetArray = new JsonArray();
    if (rowSet != null) {
      int pos = 0;
      Row row = rowSet.iterator().next();
      int count = row.getInteger(pos);
      for (String [] facetEntry : facets) {
        pos++;
        JsonObject facetObj = null;
        final String facetType = facetEntry[0];
        final String facetValue = facetEntry[1];
        for (int i = 0; i < facetArray.size(); i++) {
          facetObj = facetArray.getJsonObject(i);
          if (facetType.equals(facetObj.getString("type"))) {
            break;
          }
          facetObj = null;
        }
        if (facetObj == null) {
          facetObj = new JsonObject();
          facetObj.put("type", facetType);
          facetObj.put("facetValues", new JsonArray());
          facetArray.add(facetObj);
        }
        JsonArray facetValues = facetObj.getJsonArray("facetValues");
        facetValues.add(new JsonObject()
            .put("value", facetValue)
            .put("count", row.getInteger(pos)));
      }
      resultInfo.put("totalRecords", count);
    }
    JsonArray diagnostics = new JsonArray();
    if (diagnostic != null) {
      diagnostics.add(new JsonObject().put("message", diagnostic));
    }
    resultInfo.put("diagnostics", diagnostics);
    resultInfo.put("facets", facetArray);
    ctx.response().write("], \"resultInfo\": " + resultInfo.encode() + "}");
    ctx.response().end();
  }

  @java.lang.SuppressWarnings({"squid:S107"})  // too many arguments
  Future<Void> streamResult(RoutingContext ctx, SqlConnection sqlConnection,
      String query, String cnt, Tuple tuple, String property, List<String[]> facets,
      Function<Row, Future<JsonObject>> handler) {

    return sqlConnection.prepare(query)
        .compose(pq ->
            sqlConnection.begin().compose(tx -> {
              ctx.response().setChunked(true);
              ctx.response().putHeader("Content-Type", "application/json");
              ctx.response().write("{ \"" + property + "\" : [");
              AtomicBoolean first = new AtomicBoolean(true);
              RowStream<Row> stream = pq.createStream(sqlStreamFetchSize, tuple);
              stream.handler(row -> {
                stream.pause();
                Future<JsonObject> f = handler.apply(row);
                f.onSuccess(response -> {
                  if (!first.getAndSet(false)) {
                    ctx.response().write(",");
                  }
                  ctx.response().write(copyWithoutNulls(response).encode());
                  stream.resume();
                });
                f.onFailure(e -> {
                  log.info("failure {}", e.getMessage(), e);
                  stream.resume();
                });
              });
              stream.endHandler(end -> {
                Future<RowSet<Row>> cntFuture = cnt != null
                    ? sqlConnection.preparedQuery(cnt).execute(tuple)
                    : Future.succeededFuture(null);
                cntFuture
                    .onSuccess(cntRes -> resultFooter(ctx, cntRes, facets, null))
                    .onFailure(f -> {
                      log.error(f.getMessage(), f);
                      resultFooter(ctx, null, facets, f.getMessage());
                    })
                    .eventually(() -> tx.commit().compose(y -> sqlConnection.close()));
              });
              stream.exceptionHandler(e -> {
                log.error("stream error", e);
                resultFooter(ctx, null, facets, e.getMessage());
                tx.commit().compose(y -> sqlConnection.close());
              });
              return Future.succeededFuture();
            })
        );
  }

  Future<Void> streamResult(RoutingContext ctx, String distinct,  String from,
      String orderByClause, String property, Function<Row, Future<JsonObject>> handler) {

    return streamResult(ctx, distinct, distinct, Tuple.tuple(), List.of(from),
        Collections.emptyList(), orderByClause, property, handler);
  }

  Future<Void> streamResult(RoutingContext ctx, String distinct,
      Tuple tuple, String from, String orderByClause, String property,
      Function<Row, Future<JsonObject>> handler) {

    return streamResult(ctx, distinct, distinct, tuple, List.of(from),
        Collections.emptyList(), orderByClause, property, handler);
  }

  @java.lang.SuppressWarnings({"squid:S107"})  // too many arguments
  Future<Void> streamResult(RoutingContext ctx, String distinctMain,
      String distinctCount, Tuple tuple, List<String> fromList, List<String[]> facets,
      String orderByClause, String property, Function<Row, Future<JsonObject>> handler) {

    Integer offset = Integer.parseInt(Util.getQueryParameter(ctx, "offset", "0"));
    Integer limit = Integer.parseInt(Util.getQueryParameter(ctx, "limit", "10"));
    String count = Util.getQueryParameter(ctx, "count");
    String query = "SELECT " + (distinctMain != null ? "DISTINCT ON (" + distinctMain + ")" : "")
        + " * FROM " + fromList.get(0)
        + (orderByClause == null ?  "" : " ORDER BY " + orderByClause)
        + " LIMIT " + limit + " OFFSET " + offset;
    boolean exact = "exact".equals(count);
    log.info("query={}", query);
    StringBuilder countQuery = new StringBuilder("SELECT");
    if (exact) {
      int pos = 0;
      for (String from : fromList) {
        if (pos > 0) {
          countQuery.append(",\n");
        }
        countQuery.append("(SELECT COUNT("
            + (distinctCount != null ? "DISTINCT " + distinctCount : "*")
            + ") FROM " + from + ") AS cnt" + pos);
        pos++;
      }
      log.info("cnt={}", countQuery);
    }
    String countQueryString = exact ? countQuery.toString() : null;
    return pool.getConnection()
        .compose(sqlConnection -> streamResult(ctx, sqlConnection, query, countQueryString,
            tuple, property, facets, handler)
            .onFailure(x -> sqlConnection.close()));
  }

  Future<ModuleExecutable> getTransformer(RoutingContext ctx) {
    return selectOaiConfig()
        .compose(oaiCfg -> {
          if (oaiCfg == null) {
            return Future.succeededFuture(null);
          }
          String transformerProp = oaiCfg.getString("transformer");
          if (transformerProp == null) {
            return Future.succeededFuture(null);
          }
          ModuleInvocation invocation = new ModuleInvocation(transformerProp);
          return selectCodeModuleEntity(invocation.getModuleName())
              .compose(entity -> {
                if (entity == null) {
                  return Future.failedFuture("Transformer module '"
                    + invocation.getModuleName() + "' not found");
                }
                return ModuleCache.getInstance().lookup(ctx.vertx(), TenantUtil.tenant(ctx), entity)
                          .map(mod -> new ModuleExecutable(mod, invocation));
              });
        });
  }

  Future<Integer> getTotalRecords(PgCqlQuery pgCqlQuery) {
    String sqlWhere = pgCqlQuery.getWhereClause();
    final String wClause = sqlWhere == null ? "" : " WHERE " + sqlWhere;
    String sqlQuery = "SELECT COUNT(*) FROM " + getClusterMetaTable() + wClause;
    return getPool()
        .withConnection(conn -> conn.query(sqlQuery)
            .execute()
            .map(res -> res.iterator().next().getInteger(0)));
  }

  Future<Void> getMarcxmlRecords(RoutingContext ctx, PgCqlQuery pgCqlQuery,
      int offset, int limit, Function<String, Future<Void>> handler) {
    String sqlWhere = pgCqlQuery.getWhereClause();
    final String wClause = sqlWhere == null ? "" : " WHERE " + sqlWhere;
    String sqlQuery = "SELECT * FROM " + getClusterMetaTable() + wClause
        + " LIMIT " + limit + " OFFSET " + offset;
    return getMarcxmlRecords(ctx, sqlQuery, handler);
  }

  private Future<Void> getMarcxmlRecords(RoutingContext ctx, String sqlQuery,
      Function<String, Future<Void>> handler) {
    return getTransformer(ctx).compose(transformer -> {
      log.info("SQL Query: {}", sqlQuery);
      return getPool()
          .withConnection(conn -> conn.query(sqlQuery)
              .execute()
              .compose(res -> {
                RowIterator<Row> iterator = res.iterator();
                Future<Void> future = Future.succeededFuture();
                while (iterator.hasNext()) {
                  Row row = iterator.next();
                  future = future.compose(x -> {
                    ClusterRecordItem cr = new ClusterRecordItem(row);
                    return cr.populateCluster(this, conn, true)
                      .compose(cb -> ClusterMarcXml.getClusterMarcXml(cb, transformer, ctx.vertx())
                      .compose(marcxml -> handler.apply(marcxml)));
                  });
                }
                return future;
              }
        ));
    });
  }

  Future<Row> getClusterRecord(UUID clusterId) {
    String sqlQuery = "SELECT * FROM " + getClusterMetaTable() + " WHERE cluster_id = $1";
    return getPool()
        .withConnection(conn -> conn.preparedQuery(sqlQuery)
            .execute(Tuple.of(clusterId))
            .compose(res -> {
              RowIterator<Row> iterator = res.iterator();
              if (!iterator.hasNext()) {
                return Future.succeededFuture(null);
              }
              Row row = iterator.next();
              return Future.succeededFuture(row);
            }));
  }

}
