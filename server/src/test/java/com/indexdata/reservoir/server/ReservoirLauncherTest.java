package com.indexdata.reservoir.server;

import static org.junit.Assert.assertEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

public class ReservoirLauncherTest {

  @BeforeAll
  public static void setUpBeforeClass() throws Exception {
    ReservoirLauncher.envLookup = k -> {
      switch (k) {
        case "SOME_INTEGER_PROPERTY_SET":
          return "42";
        case "SOME_STRING_PROPERTY_SET":
          return "stringValue";
        case "SOME_BOOLEAN_PROPERTY_SET":
          return "true";
        default:
          return System.getenv(k);
      }
    };
  }

  @Test
  public void testMainFail() throws Exception {
    String[] args = {"-conf", "\"port\":9230}"};
    assertEquals(15, ReservoirLauncher.launch(args));
  }

  @Test
  public void testPropToEnv() {
    assertEquals("METRICS_PROMETHEUS_PORT", ReservoirLauncher.propToEnv("metrics.prometheus.port"));
    assertEquals("SOME_OTHER_PROPERTY", ReservoirLauncher.propToEnv("some.other.property"));
  }

  @Test
  public void testGetSysConfOrEnvString() {
    String result = ReservoirLauncher.getSysConfOrEnvString("some.property", "default", new JsonObject());
    assertEquals("default", result);
  }

  @Test
  public void testGetSysConfOrEnvStringSet() {
    String result = ReservoirLauncher.getSysConfOrEnvString("some.string.property.set", "default", new JsonObject());
    assertEquals("stringValue", result);
  }

  @Test
  public void testGetSysConfOrEnvInteger() {
    Integer result = ReservoirLauncher.getSysConfOrEnvInteger("some.integer.property", -1, new JsonObject());
    assertEquals(Integer.valueOf(-1), result);
  }

  @Test
  public void testGetSysConfOrEnvIntegerSet() {
    Integer result = ReservoirLauncher.getSysConfOrEnvInteger("some.integer.property.set", -1, new JsonObject());
    assertEquals(Integer.valueOf(42), result);
  }

  @Test
  public void testGetSysConfOrEnvBoolean() {
    Boolean result = ReservoirLauncher.getSysConfOrEnvBoolean("some.boolean.property", true, new JsonObject());
    assertEquals(Boolean.TRUE, result);
    result = ReservoirLauncher.getSysConfOrEnvBoolean("some.boolean.property.set", false, new JsonObject());
    assertEquals(Boolean.TRUE, result);
  }

}
