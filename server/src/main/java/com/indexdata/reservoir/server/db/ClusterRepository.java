package com.indexdata.reservoir.server.db;

import com.indexdata.reservoir.server.CqlFields;
import com.indexdata.reservoir.server.ParsedCql;
import com.indexdata.reservoir.server.ResumptionToken;
import com.indexdata.reservoir.server.entity.Cluster;
import com.indexdata.reservoir.server.entity.ClusterMeta;
import com.indexdata.reservoir.server.entity.GlobalRecord;
import com.indexdata.reservoir.server.entity.PoolStats;
import com.indexdata.reservoir.util.SourceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL repository for {@code cluster_meta}, {@code cluster_records}, and
 * {@code cluster_values}.
 */
public final class ClusterRepository {

  public static final String META_COLUMNS = "cluster_id, match_key_config_id, datestamp";
  public static final String QUALIFIED_META_COLUMNS =
      "cluster_meta.cluster_id, cluster_meta.match_key_config_id, cluster_meta.datestamp";

  /** Hook for executing cluster write queries with optional diagnostics/instrumentation. */
  @FunctionalInterface
  public interface QueryExecutor {
    Future<RowSet<Row>> execute(SqlConnection conn, String stage, String sql, Tuple params);
  }

  private final TenantDb db;

  public ClusterRepository(TenantDb db) {
    this.db = db;
  }

  private Tables tables() {
    return db.tables();
  }

  /** Map a cluster_meta row into a ClusterMeta domain record. */
  public static ClusterMeta metaFromRow(Row row) {
    return new ClusterMeta(
        row.getUUID("cluster_id"),
        row.getString("match_key_config_id"),
        row.getLocalDateTime("datestamp"));
  }

  /** Fetch cluster metadata by cluster id. */
  public Future<Optional<ClusterMeta>> findMetaById(SqlClient client, UUID clusterId) {
    String sql = "SELECT " + META_COLUMNS + " FROM " + tables().clusterMeta()
        + " WHERE cluster_id = $1";
    return client.preparedQuery(sql)
        .execute(Tuple.of(clusterId))
        .map(rows -> {
          RowIterator<Row> iterator = rows.iterator();
          return iterator.hasNext() ? Optional.of(metaFromRow(iterator.next())) : Optional.empty();
        });
  }

  /** Load a full cluster (records, datestamp, and match values) by cluster id. */
  public Future<Optional<Cluster>> findById(SqlConnection conn, UUID clusterId) {
    String recordsSql = """
        SELECT g.id, g.local_id, g.source_id, g.source_version, g.payload, m.datestamp
        FROM %s AS g
        INNER JOIN %s AS r ON g.id = r.record_id
        LEFT JOIN %s AS m ON m.cluster_id = r.cluster_id
        WHERE r.cluster_id = $1
        """.formatted(tables().globalRecords(), tables().clusterRecords(), tables().clusterMeta());
    String valuesSql = "SELECT match_value FROM " + tables().clusterValues()
        + " WHERE cluster_id = $1";
    return conn.preparedQuery(recordsSql)
        .execute(Tuple.of(clusterId))
        .compose(rows -> {
          LocalDateTime datestamp = null;
          List<GlobalRecord> records = new ArrayList<>();
          for (Row row : rows) {
            if (datestamp == null) {
              datestamp = row.getLocalDateTime("datestamp");
            }
            records.add(GlobalRecordRepository.fromRow(row));
          }
          if (records.isEmpty()) {
            return Future.succeededFuture(Optional.<Cluster>empty());
          }
          LocalDateTime finalDatestamp = datestamp;
          return conn.preparedQuery(valuesSql)
              .execute(Tuple.of(clusterId))
              .map(valueRows -> {
                List<String> matchValues = new ArrayList<>();
                valueRows.forEach(r -> matchValues.add(r.getString("match_value")));
                return Optional.of(Cluster.of(clusterId, finalDatestamp, records, matchValues));
              });
        });
  }

  /**
   * Populate a cluster for OAI-PMH / SRU dissemination (replaces {@code ClusterRecordItem}).
   * Returns {@link Optional#empty()} if the cluster has no live global records (deleted).
   * Leaves {@code datestamp} unset on the returned {@link Cluster} to match the legacy
   * transformer input contract (OAI headers read datestamp from {@link ClusterMeta}).
   */
  public Future<Optional<Cluster>> findForDissemination(SqlConnection conn, UUID clusterId,
      boolean withMetadata) {
    String recordsSql = """
        SELECT g.id, g.local_id, g.source_id, g.source_version, g.payload
        FROM %s AS g
        INNER JOIN %s AS r ON r.record_id = g.id
        WHERE r.cluster_id = $1
        """.formatted(tables().globalRecords(), tables().clusterRecords());
    return conn.preparedQuery(recordsSql)
        .execute(Tuple.of(clusterId))
        .compose(rows -> {
          List<GlobalRecord> records = new ArrayList<>();
          rows.forEach(row -> records.add(GlobalRecordRepository.fromRow(row)));
          if (records.isEmpty()) {
            return Future.succeededFuture(Optional.<Cluster>empty());
          }
          if (!withMetadata) {
            return Future.succeededFuture(
                Optional.of(Cluster.of(clusterId, null, records, null)));
          }
          String valuesSql = "SELECT match_value FROM " + tables().clusterValues()
              + " WHERE cluster_id = $1";
          return conn.preparedQuery(valuesSql)
              .execute(Tuple.of(clusterId))
              .map(valueRows -> {
                List<String> matchValues = new ArrayList<>();
                valueRows.forEach(r -> matchValues.add(r.getString("match_value")));
                return Optional.of(Cluster.of(clusterId, null, records, matchValues));
              });
        });
  }

  /** Fetch the ordered page of distinct cluster IDs matching a pool id and CQL filter. */
  public Future<List<UUID>> findClusterIdsPage(SqlConnection conn, String poolId,
      ParsedCql cql, int offset, int limit) {
    SqlBuilder idQuery = buildClusterFilterBase(
        SqlBuilder.selectDistinctOn(
            Tables.CLUSTER_RECORDS + ".cluster_id",
            Tables.CLUSTER_RECORDS + ".cluster_id"),
        poolId, cql, true);
    idQuery.orderBy(cql.orderBy()).limit(limit).offset(offset);
    SqlBuilder.SqlQuery builtIds = idQuery.build();

    return conn.preparedQuery(builtIds.sql())
        .execute(builtIds.params())
        .map(idRows -> {
          List<UUID> clusterIds = new ArrayList<>();
          idRows.forEach(row -> clusterIds.add(row.getUUID("cluster_id")));
          return clusterIds;
        });
  }

  /** Count distinct clusters matching a pool id and parsed CQL filter. */
  public Future<Integer> countClusters(SqlClient client, String poolId, ParsedCql cql) {
    SqlBuilder countQuery = buildClusterFilterBase(
        SqlBuilder.select("COUNT(DISTINCT " + Tables.CLUSTER_RECORDS + ".cluster_id)"),
        poolId, cql, false);
    SqlBuilder.SqlQuery built = countQuery.build();
    return client.preparedQuery(built.sql())
        .execute(built.params())
        .map(rows -> rows.iterator().next().getInteger(0));
  }

  private SqlBuilder buildClusterFilterBase(SqlBuilder select, String poolId, ParsedCql cql,
      boolean includeSort) {
    select.from(tables().clusterRecords());
    boolean needMatchValue = includeSort
        ? cql.uses(CqlFields.MATCH_VALUE) : cql.filtersOn(CqlFields.MATCH_VALUE);
    if (needMatchValue) {
      select.leftJoin(tables().clusterValues(),
          tables().clusterValues() + ".cluster_id = " + tables().clusterRecords() + ".cluster_id");
    }
    boolean needGlobalRecords = includeSort
        ? cql.usesGlobalRecords() : cql.filtersOnGlobalRecords();
    if (needGlobalRecords) {
      select.leftJoin(tables().globalRecords(),
          tables().clusterRecords() + ".record_id = " + tables().globalRecords() + ".id");
    }
    select.where(tables().clusterRecords() + ".match_key_config_id = ?", poolId);
    if (cql.where() != null) {
      select.andRaw(cql.where());
    }
    return select;
  }

  /** Batch-load full clusters for a chunk of cluster IDs in 2 queries, preserving ID order. */
  public Future<List<Cluster>> loadClustersByIds(SqlConnection conn, List<UUID> orderedIds) {
    if (orderedIds.isEmpty()) {
      return Future.succeededFuture(List.of());
    }
    UUID[] idArray = orderedIds.toArray(UUID[]::new);
    String recordsSql = """
        SELECT r.cluster_id, m.datestamp,
               g.id, g.local_id, g.source_id, g.source_version, g.payload
        FROM %s AS r
        INNER JOIN %s AS g ON g.id = r.record_id
        LEFT JOIN %s AS m ON m.cluster_id = r.cluster_id
        WHERE r.cluster_id = ANY($1)
        """.formatted(tables().clusterRecords(), tables().globalRecords(), tables().clusterMeta());
    String valuesSql = """
        SELECT cluster_id, match_value
        FROM %s
        WHERE cluster_id = ANY($1)
        """.formatted(tables().clusterValues());

    Tuple idTuple = Tuple.tuple().addArrayOfUUID(idArray);
    return conn.preparedQuery(recordsSql).execute(idTuple)
        .compose(recordRows -> {
          Map<UUID, LocalDateTime> datestamps = new HashMap<>();
          Map<UUID, List<GlobalRecord>> recordsByCluster = new HashMap<>();
          for (Row row : recordRows) {
            UUID cid = row.getUUID("cluster_id");
            datestamps.putIfAbsent(cid, row.getLocalDateTime("datestamp"));
            recordsByCluster.computeIfAbsent(cid, _ -> new ArrayList<>())
                .add(GlobalRecordRepository.fromRow(row));
          }
          return conn.preparedQuery(valuesSql).execute(idTuple)
              .map(valueRows -> {
                Map<UUID, List<String>> valuesByCluster = new HashMap<>();
                for (Row row : valueRows) {
                  valuesByCluster.computeIfAbsent(row.getUUID("cluster_id"), _ -> new ArrayList<>())
                      .add(row.getString("match_value"));
                }
                List<Cluster> clusters = new ArrayList<>(orderedIds.size());
                for (UUID cid : orderedIds) {
                  clusters.add(Cluster.of(
                      cid,
                      datestamps.get(cid),
                      recordsByCluster.getOrDefault(cid, List.of()),
                      valuesByCluster.getOrDefault(cid, List.of())));
                }
                return clusters;
              });
        });
  }

  /**
   * Build the OAI-PMH ListRecords/ListIdentifiers cursor query using {@link SqlBuilder}.
   */
  public SqlBuilder.SqlQuery buildOaiListQuery(String poolId, ResumptionToken token,
      LocalDateTime from, LocalDateTime until) {
    SqlBuilder query = SqlBuilder.select(META_COLUMNS)
        .from(tables().clusterMeta())
        .where("match_key_config_id = ?", poolId);
    if (token != null) {
      LocalDateTime tokenFrom = token.getFrom();
      if (token.getId() == null) {
        query.and("datestamp >= ?", tokenFrom);
      } else {
        query.and("(datestamp = ? AND cluster_id >= ? OR datestamp > ?)",
            tokenFrom, token.getId(), tokenFrom);
      }
    } else if (from != null) {
      query.and("datestamp >= ?", from);
    }
    if (until != null) {
      query.and("datestamp < ?", until);
    }
    query.orderBy("datestamp, cluster_id");
    return query.build();
  }

  /** Count distinct clusters matching an SRU WHERE clause. */
  public Future<Integer> countBySruWhere(SqlClient client, String sqlWhere,
      boolean joinClusterValues) {
    SqlBuilder query = SqlBuilder.select(
            "COUNT(DISTINCT " + Tables.CLUSTER_META + ".cluster_id)")
        .from(tables().clusterMeta());
    if (joinClusterValues) {
      query.leftJoin(tables().clusterValues(),
          tables().clusterValues() + ".cluster_id = " + tables().clusterMeta() + ".cluster_id");
    }
    query.whereRaw(sqlWhere);
    SqlBuilder.SqlQuery built = query.build();
    return client.preparedQuery(built.sql())
        .execute(built.params())
        .map(rows -> rows.iterator().next().getInteger(0));
  }

  /** Fetch a page of cluster metadata entries matching an SRU WHERE clause. */
  public Future<List<ClusterMeta>> findMetaPageBySruWhere(SqlClient client, String sqlWhere,
      boolean joinClusterValues, int offset, int limit) {
    SqlBuilder query = SqlBuilder.selectDistinctOn(
            Tables.CLUSTER_META + ".cluster_id",
            QUALIFIED_META_COLUMNS)
        .from(tables().clusterMeta());
    if (joinClusterValues) {
      query.leftJoin(tables().clusterValues(),
          tables().clusterValues() + ".cluster_id = " + tables().clusterMeta() + ".cluster_id");
    }
    query.whereRaw(sqlWhere)
        .limit(limit)
        .offset(offset);
    SqlBuilder.SqlQuery built = query.build();
    return client.preparedQuery(built.sql())
        .execute(built.params())
        .map(rows -> {
          List<ClusterMeta> list = new ArrayList<>();
          rows.forEach(row -> list.add(metaFromRow(row)));
          return list;
        });
  }

  /** Bump datestamp on clusters matching the given CQL WHERE clause. */
  public Future<Integer> touchByCql(SqlClient client, String sqlWhere) {
    String sql = """
        UPDATE %s
        SET datestamp = $1
        FROM %s, %s
        WHERE cluster_meta.cluster_id = cluster_records.cluster_id
          AND cluster_records.record_id = global_records.id
          AND %s
        """.formatted(tables().clusterMeta(), tables().globalRecords(),
            tables().clusterRecords(), sqlWhere);
    return client.preparedQuery(sql)
        .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC)))
        .map(RowSet::rowCount);
  }

  /** Bump cluster datestamps affected by deleting a single global record. */
  public Future<Void> touchForDeletedRecord(SqlClient client, String localId, SourceId sourceId,
      int sourceVersion) {
    String sql = """
        UPDATE %s AS m
        SET datestamp = $4
        FROM %s, %s AS r
        WHERE m.cluster_id = r.cluster_id AND r.record_id = id
          AND local_id = $1 AND source_id = $2 AND source_version = $3
        """.formatted(tables().clusterMeta(), tables().globalRecords(), tables().clusterRecords());
    return client.preparedQuery(sql)
        .execute(Tuple.of(localId, sourceId.toString(), sourceVersion,
            LocalDateTime.now(ZoneOffset.UTC)))
        .mapEmpty();
  }

  /** Bump cluster datestamps affected by bulk-deleting global records matching a WHERE clause. */
  public Future<Integer> touchForDeletedRecordsWhere(SqlClient client, String sqlWhere,
      String negatedSourceVersionWhere) {
    StringBuilder sql = new StringBuilder("""
        UPDATE %s AS m
        SET datestamp = $1
        FROM %s, %s AS r
        WHERE m.cluster_id = r.cluster_id AND r.record_id = id""".formatted(
            tables().clusterMeta(), tables().globalRecords(), tables().clusterRecords()));
    if (sqlWhere != null) {
      sql.append(" AND ").append(sqlWhere);
      if (negatedSourceVersionWhere != null) {
        sql.append(" AND NOT EXISTS (SELECT 1 FROM ")
            .append(tables().globalRecords()).append(", ")
            .append(tables().clusterRecords()).append(" AS r")
            .append(" WHERE m.cluster_id = r.cluster_id AND r.record_id = id")
            .append(" AND ").append(negatedSourceVersionWhere).append(')');
      }
    }
    return client.preparedQuery(sql.toString())
        .execute(Tuple.of(LocalDateTime.now(ZoneOffset.UTC)))
        .map(RowSet::rowCount);
  }

  /**
   * Build the exact-lookup {@code UNION ALL} query for cluster match values.
   * Keeps exact index conditions on (match_key_config_id, match_value) for PostgreSQL 16.
   */
  public SqlBuilder.SqlQuery buildClusterValueLookupQuery(String poolId, Collection<String> keys) {
    List<Object> params = new ArrayList<>(keys.size() + 1);
    params.add(poolId);
    StringBuilder q = new StringBuilder();
    int no = 2;
    for (String key : keys) {
      if (no > 2) {
        q.append(" UNION ALL ");
      }
      q.append("SELECT cluster_id, match_value FROM ").append(tables().clusterValues())
          .append(" WHERE match_key_config_id = $1 AND match_value = $").append(no++);
      params.add(key);
    }
    return new SqlBuilder.SqlQuery(q.toString(), Tuple.from(params));
  }

  /** Insert new match values into a cluster using a single UNNEST array statement. */
  public Future<Void> addValuesToCluster(SqlConnection conn, UUID clusterId, String poolId,
      List<String> newValues, QueryExecutor executor) {
    if (newValues.isEmpty()) {
      return Future.succeededFuture();
    }
    String sql = """
        INSERT INTO %s (cluster_id, match_key_config_id, match_value)
        SELECT $1, $2, v FROM UNNEST($3::text[]) AS v
        """.formatted(tables().clusterValues());
    Tuple params = Tuple.tuple()
        .addUUID(clusterId)
        .addString(poolId)
        .addArrayOfString(newValues.toArray(String[]::new));
    return executor.execute(conn, "write.values", sql, params)
        .mapEmpty();
  }

  /** Insert a new cluster_meta row. */
  public Future<UUID> createMetaEntry(SqlConnection conn, UUID clusterId, String poolId,
      QueryExecutor executor) {
    String sql = """
        INSERT INTO %s (cluster_id, datestamp, match_key_config_id)
        VALUES ($1, $2, $3)
        """.formatted(tables().clusterMeta());
    return executor.execute(conn, "write.meta", sql,
            Tuple.of(clusterId, LocalDateTime.now(ZoneOffset.UTC), poolId))
        .map(clusterId);
  }

  /** Update datestamp on multiple clusters via a single ANY(uuid[]) statement. */
  public Future<Void> updateMetaEntries(SqlConnection conn, Collection<UUID> clusterIds,
      QueryExecutor executor) {
    if (clusterIds.isEmpty()) {
      return Future.succeededFuture();
    }
    String sql = "UPDATE " + tables().clusterMeta()
        + " SET datestamp = $1 WHERE cluster_id = ANY($2)";
    Tuple params = Tuple.tuple()
        .addLocalDateTime(LocalDateTime.now(ZoneOffset.UTC))
        .addArrayOfUUID(clusterIds.toArray(UUID[]::new));
    return executor.execute(conn, "update.meta", sql, params)
        .mapEmpty();
  }

  /** Reassign values and records from merged clusters into the surviving target cluster. */
  public Future<Void> mergeClusters(SqlConnection conn, UUID targetClusterId,
      Collection<UUID> sourceClusterIds, QueryExecutor executor) {
    if (sourceClusterIds.isEmpty()) {
      return Future.succeededFuture();
    }
    UUID[] sources = sourceClusterIds.toArray(UUID[]::new);
    Tuple params = Tuple.tuple()
        .addUUID(targetClusterId)
        .addArrayOfUUID(sources);
    String updateValuesSql = "UPDATE " + tables().clusterValues()
        + " SET cluster_id = $1 WHERE cluster_id = ANY($2)";
    String updateRecordsSql = "UPDATE " + tables().clusterRecords()
        + " SET cluster_id = $1 WHERE cluster_id = ANY($2)";
    return executor.execute(conn, "merge.values", updateValuesSql, params)
        .compose(_ -> executor.execute(conn, "merge.records", updateRecordsSql, params))
        .mapEmpty();
  }

  /** Upsert a record's cluster assignment for a pool. */
  public Future<Void> upsertClusterRecord(SqlConnection conn, UUID globalId, String poolId,
      UUID clusterId, QueryExecutor executor) {
    String sql = """
        INSERT INTO %s (record_id, match_key_config_id, cluster_id)
        VALUES ($1, $2, $3)
        ON CONFLICT (record_id, match_key_config_id)
        DO UPDATE SET record_id = $1, match_key_config_id = $2, cluster_id = $3
        """.formatted(tables().clusterRecords());
    return executor.execute(conn, "write.record", sql, Tuple.of(globalId, poolId, clusterId))
        .mapEmpty();
  }

  /** Remove a record from its cluster for a pool and touch the cluster datestamp. */
  public Future<Void> removeClusterRecord(SqlConnection conn, UUID globalId, String poolId,
      QueryExecutor executor) {
    String touchSql = """
        UPDATE %s
        SET datestamp = $1
        FROM %s
        WHERE cluster_meta.cluster_id = cluster_records.cluster_id
          AND cluster_records.match_key_config_id = $2
          AND cluster_records.record_id = $3
        """.formatted(tables().clusterMeta(), tables().clusterRecords());
    String deleteSql = "DELETE FROM " + tables().clusterRecords()
        + " WHERE record_id = $1 AND match_key_config_id = $2";
    return executor.execute(conn, "remove.meta", touchSql,
            Tuple.of(LocalDateTime.now(ZoneOffset.UTC), poolId, globalId))
        .compose(_ -> executor.execute(conn, "remove.record", deleteSql,
            Tuple.of(globalId, poolId)))
        .mapEmpty();
  }

  /**
   * Compute clustering statistics for a pool using {@link RowStreamer} with proper transaction
   * commit/rollback and stream cleanup.
   */
  public Future<PoolStats> computeStats(String poolId, int fetchSize) {
    String sql = """
        SELECT r.cluster_id, r.record_id, v.match_value
        FROM %s AS r
        LEFT JOIN %s AS v ON v.cluster_id = r.cluster_id
        WHERE r.match_key_config_id = $1
        ORDER BY r.cluster_id
        """.formatted(tables().clusterRecords(), tables().clusterValues());
    SqlBuilder.SqlQuery query = new SqlBuilder.SqlQuery(sql, Tuple.of(poolId));

    return db.withConnection(conn -> {
      StatsAccumulator acc = new StatsAccumulator();
      return RowStreamer.streamSync(conn, query, fetchSize, acc::accept, acc::finish);
    });
  }

  private static final class StatsAccumulator {
    UUID currentClusterId;
    int clustersTotal;
    final Set<String> values = new HashSet<>();
    final Set<UUID> recordIds = new HashSet<>();
    final Map<Integer, Integer> matchValuesPerCluster = new LinkedHashMap<>();
    final Map<Integer, Integer> recordsPerCluster = new LinkedHashMap<>();
    final Map<Integer, List<UUID>> recordsPerClusterSample = new LinkedHashMap<>();

    void accept(Row row) {
      UUID clusterId = row.getUUID("cluster_id");
      if (!clusterId.equals(currentClusterId)) {
        flushCluster();
        clustersTotal++;
        values.clear();
        recordIds.clear();
        currentClusterId = clusterId;
      }
      String matchValue = row.getString("match_value");
      if (matchValue != null) {
        values.add(matchValue);
      }
      recordIds.add(row.getUUID("record_id"));
    }

    private void flushCluster() {
      if (currentClusterId != null) {
        matchValuesPerCluster.merge(values.size(), 1, Integer::sum);
        int size = recordIds.size();
        recordsPerCluster.merge(size, 1, Integer::sum);
        List<UUID> samples = recordsPerClusterSample.computeIfAbsent(size, _ -> new ArrayList<>());
        if (samples.size() < 3) {
          samples.add(currentClusterId);
        }
      }
    }

    PoolStats finish() {
      flushCluster();
      int totalRecords = 0;
      for (var entry : recordsPerCluster.entrySet()) {
        totalRecords += entry.getKey() * entry.getValue();
      }
      Map<Integer, List<UUID>> immutableSamples = new LinkedHashMap<>();
      recordsPerClusterSample.forEach((k, v) -> immutableSamples.put(k, List.copyOf(v)));
      return new PoolStats(
          totalRecords,
          clustersTotal,
          matchValuesPerCluster,
          recordsPerCluster,
          immutableSamples);
    }
  }
}
