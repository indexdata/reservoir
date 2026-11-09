package com.indexdata.reservoir.server;

import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

public class ReservoirLauncherTest {

  private Function<String, String> originalEnvLookup;

  @BeforeEach
  public void setUp() {
    originalEnvLookup = ReservoirLauncher.envLookup;
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

  @AfterEach
  public void tearDown() {
    ReservoirLauncher.envLookup = originalEnvLookup;
  }

  @Test
  public void testHttpPortEnvironmentAndConfig() {
    ReservoirLauncher.envLookup = k -> "HTTP_PORT".equals(k) ? "18081" : null;
    assertEquals("18081", ReservoirLauncher.getSysConfOrEnvString(
        "http.port", "port", "8081", new JsonObject()));
    assertEquals("9231", ReservoirLauncher.getSysConfOrEnvString(
        "http.port", "port", "8081", new JsonObject().put("port", "9231")));
    assertEquals("9232", ReservoirLauncher.getSysConfOrEnvString(
        "http.port", "port", "8081", new JsonObject()
            .put("port", "9231").put("http.port", "9232")));
    ReservoirLauncher.envLookup = k -> " ";
    assertEquals("8081", ReservoirLauncher.getSysConfOrEnvString(
        "http.port", "port", "8081", new JsonObject()));
  }

  @Test
  public void testSystemPropertyPrecedesEnvironmentAndConfig() {
    String key = "reservoir.test.port";
    String previous = System.getProperty(key);
    try {
      System.setProperty(key, "9233");
      ReservoirLauncher.envLookup = k -> "18081";
      assertEquals("9233", ReservoirLauncher.getSysConfOrEnvString(
          key, "port", "8081", new JsonObject().put(key, "9232")));
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
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
