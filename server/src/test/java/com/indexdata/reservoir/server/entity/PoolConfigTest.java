package com.indexdata.reservoir.server.entity;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

public class PoolConfigTest {
  @Test
  public void matcherInvocations() {
    PoolConfig config = new PoolConfig(new JsonObject()
        .put("id", "multi")
        .put("matcher", "first, second::matchkey"));

    assertThat(config.getMatcher(), is("first, second::matchkey"));
    assertThat(config.getMatcherInvocations(), arrayContaining("first", "second::matchkey"));
    assertThat(config.toJson().getString("matcher"), is("first, second::matchkey"));
  }

  @Test
  public void singleMatcherInvocation() {
    PoolConfig config = new PoolConfig(new JsonObject()
        .put("id", "single")
        .put("matcher", "first::matchkey"));

    assertThat(config.getMatcherInvocations(), arrayContaining("first::matchkey"));
  }

  @Test
  public void emptyMatcherInvocationIsRejected() {
    JsonObject json = new JsonObject().put("id", "invalid").put("matcher", "first, ,second");

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class, () -> new PoolConfig(json));

    assertThat(exception.getMessage(), is("Matcher module invocation cannot be empty"));
  }
}
