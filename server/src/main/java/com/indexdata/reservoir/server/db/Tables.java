package com.indexdata.reservoir.server.db;

import java.util.Objects;

/** Schema-qualified PostgreSQL table names for a single tenant. */
public record Tables(String schema) {
  public static final String GLOBAL_RECORDS = "global_records";
  public static final String POOL_CONFIG = "match_key_config";
  public static final String CLUSTER_META = "cluster_meta";
  public static final String CLUSTER_RECORDS = "cluster_records";
  public static final String CLUSTER_VALUES = "cluster_values";
  public static final String MODULE = "module";
  public static final String OAI_CONFIG = "oai_config";
  public static final String OAI_PMH_CLIENTS = "oai_pmh_clients";
  public static final String POOL_INITIALIZATION_JOBS = "pool_initialization_jobs";
  public static final String SCHEMA_MIGRATIONS = "schema_migrations";

  public Tables {
    Objects.requireNonNull(schema, "schema must not be null");
  }

  public String qualified(String tableName) {
    return schema + "." + tableName;
  }

  public String globalRecords() {
    return qualified(GLOBAL_RECORDS);
  }

  public String poolConfig() {
    return qualified(POOL_CONFIG);
  }

  public String clusterMeta() {
    return qualified(CLUSTER_META);
  }

  public String clusterRecords() {
    return qualified(CLUSTER_RECORDS);
  }

  public String clusterValues() {
    return qualified(CLUSTER_VALUES);
  }

  public String module() {
    return qualified(MODULE);
  }

  public String oaiConfig() {
    return qualified(OAI_CONFIG);
  }

  public String oaiPmhClients() {
    return qualified(OAI_PMH_CLIENTS);
  }

  public String poolInitializationJobs() {
    return qualified(POOL_INITIALIZATION_JOBS);
  }

  public String schemaMigrations() {
    return qualified(SCHEMA_MIGRATIONS);
  }
}
