package com.indexdata.reservoir.server.entity;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.indexdata.reservoir.module.ModuleInvocation;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class PoolConfigTest {
  @Test
  public void matcherInvocations() {
    PoolConfig config = PoolConfigJson.fromJson(new JsonObject()
        .put("id", "multi")
        .put("matcher", "first, second::matchkey"));

    assertThat(config.matcherString(), is("first,second::matchkey"));
    assertThat(config.matchers(), contains(
        ModuleInvocation.parse("first"),
        ModuleInvocation.parse("second::matchkey")));
    assertThat(PoolConfigJson.toJson(config).getString("matcher"), is("first,second::matchkey"));
  }

  @Test
  public void singleMatcherInvocation() {
    PoolConfig config = PoolConfigJson.fromJson(new JsonObject()
        .put("id", "single")
        .put("matcher", "first::matchkey"));

    assertThat(config.matchers(), contains(ModuleInvocation.parse("first::matchkey")));
  }

  @Test
  public void emptyMatcherInvocationIsRejected() {
    JsonObject json = new JsonObject().put("id", "invalid").put("matcher", "first, ,second");

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class, () -> PoolConfigJson.fromJson(json));

    assertThat(exception.getMessage(), is("Matcher module invocation cannot be empty"));
  }

  @Test
  public void emptyModuleNameIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> ModuleInvocation.parse(""));
    assertThrows(IllegalArgumentException.class, () -> ModuleInvocation.parse("::fn"));
    assertThrows(IllegalArgumentException.class, () -> new ModuleInvocation("", "fn"));
    assertThrows(IllegalArgumentException.class, () -> new ModuleInvocation(null, "fn"));
  }

  @Test
  public void matcherIsRequired() {
    JsonObject json = new JsonObject().put("id", "invalid");

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class, () -> PoolConfigJson.fromJson(json));

    assertThat(exception.getMessage(), is("PoolConfig matcher is required"));
  }

  @Test
  public void cqlPreservesOrderAndRejectsInvalidValues() {
    PoolConfig config = PoolConfigJson.fromJson(new JsonObject()
        .put("id", "p1")
        .put("matcher", "m1")
        .put("cql", new JsonObject().put("z", "modZ::fn").put("a", "modA")));

    assertThat(new ArrayList<>(config.cql().keySet()), contains("z", "a"));
    assertThat(config.cql().get("z"), is(ModuleInvocation.parse("modZ::fn")));
    assertThat(config.cql().get("a"), is(ModuleInvocation.parse("modA")));
    assertThat(PoolConfigJson.toJson(config).getJsonObject("cql"),
        is(new JsonObject().put("z", "modZ::fn").put("a", "modA")));

    assertThrows(IllegalArgumentException.class, () -> PoolConfigJson.fromJson(new JsonObject()
        .put("id", "p1").put("matcher", "m1")
        .put("cql", new JsonObject().put("bad", 42))));
    assertThrows(IllegalArgumentException.class, () -> PoolConfigJson.fromJson(new JsonObject()
        .put("id", "p1").put("matcher", "m1")
        .put("cql", new JsonObject().put("bad", ""))));
    assertThrows(IllegalArgumentException.class, () -> PoolConfigJson.fromJson(new JsonObject()
        .put("id", "p1").put("matcher", "m1")
        .put("cql", new JsonObject().put("bad", "::fn"))));
    assertThrows(IllegalArgumentException.class, () -> PoolConfigJson.fromJson(new JsonObject()
        .put("id", "p1").put("matcher", "m1")
        .put("cql", new JsonObject().putNull("bad"))));
  }

  @Test
  public void enumsRejectUnknownValues() {
    assertThrows(IllegalArgumentException.class, () -> MatcherArgs.fromValue("custom"));
    assertThrows(IllegalArgumentException.class, () -> UpdateMode.fromValue("custom"));
    assertThrows(IllegalArgumentException.class, () -> OaiPmhJobStatus.fromValue("custom"));
  }

  @Test
  public void poolStatsPreservesMapInsertionOrder() {
    Map<Integer, Integer> counts = new LinkedHashMap<>();
    counts.put(10, 1);
    counts.put(2, 5);
    counts.put(7, 3);
    PoolStats stats = new PoolStats(10, 9, counts, counts, Map.of());
    assertThat(new ArrayList<>(stats.recordsPerCluster().keySet()), contains(10, 2, 7));
  }
}
