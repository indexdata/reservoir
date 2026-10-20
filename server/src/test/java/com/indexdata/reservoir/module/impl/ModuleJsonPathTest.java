package com.indexdata.reservoir.module.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleJsonPathTest {

  private final ModuleJsonPath module = new ModuleJsonPath("$.keys");

  @Test
  void stringListDeduplicatesKeys() {
    JsonObject input = new JsonObject()
        .put("keys", new JsonArray().add("abc").add("def").add("abc"));

    assertThat(module.executeAsCollection(null, input), is(Set.of("abc", "def")));
  }

  @Test
  void mixedListDiscardsAllKeys() {
    Object[] invalidValues = {123, true, null, new JsonObject(), new JsonArray()};
    for (Object invalid : invalidValues) {
      JsonObject input = new JsonObject()
          .put("keys", new JsonArray().add("abc").add(invalid).add("def"));

      assertThat("Invalid value: " + invalid,
          module.executeAsCollection(null, input), is(Set.of()));
    }
  }

  @Test
  void scalarStringBecomesKey() {
    assertThat(module.executeAsCollection(null, new JsonObject().put("keys", "abc")),
        is(Set.of("abc")));
  }

  @Test
  void absentEmptyOrNonStringValuesProduceNoKeys() {
    JsonObject[] inputs = {
        new JsonObject(),
        new JsonObject().put("keys", new JsonArray()),
        new JsonObject().put("keys", 123),
        new JsonObject().put("keys", null)
    };
    for (JsonObject input : inputs) {
      assertThat("Input: " + input, module.executeAsCollection(null, input), is(Set.of()));
    }
  }
}
