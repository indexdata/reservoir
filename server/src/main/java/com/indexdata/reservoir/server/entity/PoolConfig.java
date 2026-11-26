package com.indexdata.reservoir.server.entity;

import com.indexdata.reservoir.module.ModuleInvocation;
import io.vertx.core.json.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure domain record for a pool (match-key) configuration. */
public record PoolConfig(
    String id,
    MatcherArgs args,
    List<ModuleInvocation> matchers,
    String method,
    UpdateMode update,
    Map<String, ModuleInvocation> cql,
    JsonObject params
) {

  public PoolConfig {
    if (id == null) {
      throw new IllegalArgumentException("PoolConfig id is required");
    }
    if (id.contains("'") || id.contains("\"")) {
      throw new IllegalArgumentException("PoolConfig id cannot contain quotes");
    }
    if (matchers == null || matchers.isEmpty()) {
      throw new IllegalArgumentException("PoolConfig matcher is required");
    }
    matchers = List.copyOf(matchers);
    cql = cql == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(cql));
    params = params == null ? null : params.copy();
  }

  public boolean onlyPayload() {
    return args == null || args == MatcherArgs.PAYLOAD;
  }

  public boolean isManualUpdate() {
    return update == UpdateMode.MANUAL;
  }

  /**
   * Format configured matcher invocations into the canonical {@code one,two} string.
   */
  public String matcherString() {
    return ModuleInvocation.formatList(matchers);
  }
}
