package org.folio.reservoir.server;

public enum CqlFields {
  CQL_ALL_RECORDS("cql.allRecords"),
  ID("id"),
  GLOBAL_ID("globalId", Storage.GLOBAL_RECORDS_TABLE, "id"),
  LOCAL_ID("localId", Storage.GLOBAL_RECORDS_TABLE, "local_id"),
  MATCHKEY_ID("matchkeyId", Storage.CLUSTER_META_TABLE, "match_key_config_id"),
  METHOD("method", Storage.MATCH_KEY_CONFIG_TABLE, "method"),
  MATCHER("matcher", Storage.MATCH_KEY_CONFIG_TABLE, "matcher"),
  FUNCTION("function", Storage.MODULE_TABLE, "function"),
  MATCH_VALUE("matchValue", Storage.CLUSTER_VALUES_TABLE, "match_value"),
  CLUSTER_ID("clusterId", Storage.CLUSTER_RECORDS_TABLE, "cluster_id"),
  SOURCE_ID("sourceId", Storage.GLOBAL_RECORDS_TABLE, "source_id"),
  SOURCE_VERSION("sourceVersion", Storage.GLOBAL_RECORDS_TABLE, "source_version");

  private final String cqlName;
  private final String sqlTableName;
  private final String sqlColumnName;

  CqlFields(String cqlName, String sqlTableName, String sqlColumnName) {
    this.cqlName = cqlName;
    this.sqlTableName = sqlTableName;
    this.sqlColumnName = sqlColumnName;
  }

  CqlFields(String cqlName) {
    this(cqlName, null, null);
  }

  public String getCqlName() {
    return cqlName;
  }

  public String getSqllName() {
    return sqlColumnName;
  }

  public String getQualifiedSqlName() {
    return (sqlTableName != null) ? sqlTableName + "." + sqlColumnName : sqlColumnName;
  }

}
