package com.indexdata.reservoir.server.entity;

import io.vertx.core.json.JsonObject;

public class MatchKeyConfig {
  private final String id;
  private final String args;
  private final String matcher;
  private final String method;
  private final JsonObject params;
  private final String update;
  private final JsonObject cql;

  private static final String ID_LABEL = "id";
  private static final String ARGS_LABEL = "args";
  private static final String MATCHER_LABEL = "matcher";
  private static final String METHOD_LABEL = "method";
  private static final String PARAMS_LABEL = "params";
  private static final String UPDATE_LABEL = "update";
  private static final String CQL_LABEL = "cql";

  /**
   * Constructor.
   * @param id match key id
   * @param args kind of args to be passed to the matcher
   * @param cql CQL query as json object
   * @param matcher matcher code module id
   * @param method matching method
   * @param params OAI-PMH parameters as json object
   * @param update "manual" or "ingest"
   */
  public MatchKeyConfig(String id, String args, JsonObject cql, String matcher, String method,
      JsonObject params, String update) {
    this.id = id;
    this.args = args;
    this.matcher = matcher;
    this.method = method;
    this.params = params;
    this.update = update;
    this.cql = cql;
  }

  /**
   * Constructor from JsonObject.
   * @param json object with match key config data
   */
  public MatchKeyConfig(JsonObject json) {
    this.id = json.getString(ID_LABEL);
    this.args = json.getString(ARGS_LABEL);
    this.matcher = json.getString(MATCHER_LABEL);
    this.method = json.getString(METHOD_LABEL);
    this.params = json.getJsonObject(PARAMS_LABEL);
    this.update = json.getString(UPDATE_LABEL);
    this.cql = json.getJsonObject(CQL_LABEL);
  }

  public String getId() {
    return id;
  }

  public String getArgs() {
    return args;
  }

  public String getMatcher() {
    return matcher;
  }

  public String getMethod() {
    return method;
  }

  public JsonObject getParams() {
    return params;
  }

  public String getUpdate() {
    return update;
  }

  public JsonObject getCql() {
    return cql;
  }

  /**
   * Convert to JsonObject.
   * @return json object
   */
  public JsonObject toJson() {
    JsonObject json = new JsonObject();
    json.put(ID_LABEL, id);
    if (args != null) {
      json.put(ARGS_LABEL, args);
    }
    if (matcher != null) {
      json.put(MATCHER_LABEL, matcher);
    }
    if (method != null) {
      json.put(METHOD_LABEL, method);
    }
    if (params != null) {
      json.put(PARAMS_LABEL, params);
    }
    if (update != null) {
      json.put(UPDATE_LABEL, update);
    }
    if (cql != null) {
      json.put(CQL_LABEL, cql);
    }
    return json;
  }
}
