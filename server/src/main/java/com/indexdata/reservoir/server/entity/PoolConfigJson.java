package com.indexdata.reservoir.server.entity;

import com.indexdata.reservoir.module.ModuleInvocation;
import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit JSON codec for {@link PoolConfig}.
 */
public final class PoolConfigJson {

  public static final String ID = "id";
  public static final String ARGS = "args";
  public static final String MATCHER = "matcher";
  public static final String METHOD = "method";
  public static final String PARAMS = "params";
  public static final String UPDATE = "update";
  public static final String CQL = "cql";

  private PoolConfigJson() { }

  /** Decode a PoolConfig from JSON. */
  public static PoolConfig fromJson(JsonObject json) {
    return new PoolConfig(
        json.getString(ID),
        MatcherArgs.fromValue(json.getString(ARGS)),
        ModuleInvocation.parseList(json.getString(MATCHER)),
        json.getString(METHOD),
        UpdateMode.fromValue(json.getString(UPDATE)),
        decodeCql(json.getJsonObject(CQL)),
        json.getJsonObject(PARAMS));
  }

  /** Encode a PoolConfig to JSON using the canonical comma-separated matcher format. */
  public static JsonObject toJson(PoolConfig config) {
    JsonObject json = new JsonObject().put(ID, config.id());
    if (config.args() != null) {
      json.put(ARGS, config.args().value());
    }
    json.put(MATCHER, config.matcherString());
    JsonCodec.putIfPresent(json, METHOD, config.method());
    JsonCodec.putIfPresent(json, PARAMS, config.params());
    if (config.update() != null) {
      json.put(UPDATE, config.update().value());
    }
    JsonCodec.putIfPresent(json, CQL, encodeCql(config.cql()));
    return json;
  }

  /**
   * Decode the optional CQL index-to-module-invocation map, preserving insertion order.
   * A null mapping marks a disabled index and is skipped.
   */
  public static Map<String, ModuleInvocation> decodeCql(JsonObject cqlJson) {
    if (cqlJson == null) {
      return null;
    }
    Map<String, ModuleInvocation> map = new LinkedHashMap<>();
    for (String key : cqlJson.fieldNames()) {
      Object value = cqlJson.getValue(key);
      if (value == null) {
        continue;
      }
      if (!(value instanceof String invocation)) {
        throw new IllegalArgumentException(
            "CQL module invocation for '" + key + "' must be a string");
      }
      map.put(key, ModuleInvocation.parse(invocation));
    }
    return map;
  }

  /** Encode the optional CQL index-to-module-invocation map to JSON. */
  public static JsonObject encodeCql(Map<String, ModuleInvocation> cql) {
    if (cql == null) {
      return null;
    }
    JsonObject json = new JsonObject();
    cql.forEach((key, invocation) -> json.put(key, invocation.format()));
    return json;
  }
}
