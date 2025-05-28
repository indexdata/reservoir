package org.folio.reservoir.matchkey.impl;

import com.jayway.jsonpath.InvalidPathException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.util.HashSet;
import java.util.Set;

import org.folio.reservoir.matchkey.MatchKeyMethod;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

@RunWith(VertxUnitRunner.class)
public class MatchKeyJsonPathTest {

  static Vertx vertx;

  @BeforeClass
  public static void beforeClass()  {
    vertx = Vertx.vertx();
  }

  @AfterClass
  public static void afterClass(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void matchKeyJsonPathNonConfigured() {
    MatchKeyMethod matchKeyMethod = new MatchKeyJsonPath();
    JsonObject payload = new JsonObject();
    Set<String> keys = new HashSet<>();
    Exception e = Assert.assertThrows(
        IllegalArgumentException.class,
        () -> matchKeyMethod.getKeys(payload, keys));
    assertThat(e.getMessage(), is("path can not be null"));
  }

  @Test
  public void matchKeyBadPath(TestContext context) {
    MatchKeyMethod matchKeyMethod = new MatchKeyJsonPath();
    matchKeyMethod.configure(vertx, new JsonObject()).onComplete(context.asyncAssertFailure(e ->
      assertThat(e.getMessage(), is("jsonpath: expr must be given"))
    ));
  }

  @Test
  public void matchKeyJsonPathConfigureInvalidJsonPath() {
    MatchKeyMethod m = new MatchKeyJsonPath();
    JsonObject configuration = new JsonObject().put("expr", "$.fields.010.subfields[x");
    Assert.assertThrows(InvalidPathException.class,
        () -> m.configure(vertx, configuration));
  }

  @Test
  public void matchKeyJsonPathConfigureMarc(TestContext context) {
    MatchKeyMethod m = new MatchKeyJsonPath();
    m.configure(vertx, new JsonObject().put("expr", "$.marc.fields[*].010.subfields[*].a"))
        .onComplete(context.asyncAssertSuccess(s -> {

          JsonObject payload = new JsonObject()
              .put("marc", new JsonObject()
                  .put("leader", "00942nam  22002531a 4504")
                  .put("fields", new JsonArray()
                      .add(new JsonObject().put("001", "   73209622 //r823"))
                      .add(new JsonObject().put("010", new JsonObject()
                          .put("subfields", new JsonArray()
                              .add(new JsonObject().put("b", "73209622"))
                          )
                      ))
                      .add(new JsonObject().put("245", new JsonObject()
                          .put("subfields", new JsonArray()
                              .add(new JsonObject().put("a", "The Computer Bible /"))
                              .add(new JsonObject().put("c", "J. Arthur Baird, David Noel Freedman, editors." ))
                          )
                      ))
                  )
              );
          Set<String> keys = new HashSet<>();
          m.getKeys(payload, keys);
          assertThat(keys, is(empty()));

          payload = new JsonObject()
              .put("marc", new JsonObject()
                  .put("leader", "00942nam  22002531a 4504")
                  .put("fields", new JsonArray()
                      .add(new JsonObject().put("001", "   73209622 //r823"))
                      .add(new JsonObject().put("010", new JsonObject()
                          .put("subfields", new JsonArray()
                              .add(new JsonObject().put("a", "73209622"))
                              .add(new JsonObject().put("a", "73209623"))
                          )
                      ))
                      .add(new JsonObject().put("245", new JsonObject()
                          .put("subfields", new JsonArray()
                              .add(new JsonObject().put("a", "The Computer Bible /"))
                              .add(new JsonObject().put("c", "J. Arthur Baird, David Noel Freedman, editors." ))
                          )
                      ))
                  ));
          keys.clear();
          m.getKeys(payload, keys);
          assertThat(keys, containsInAnyOrder("73209622", "73209623"));
        }));
  }

  @Test
  public void matchKeyJsonPathConfigureInventory(TestContext context) {
    MatchKeyMethod m = new MatchKeyJsonPath();
    m.configure(vertx, new JsonObject().put("expr", "$.inventory.isbn[*]"))
        .onComplete(context.asyncAssertSuccess(s -> {
          JsonObject payload = new JsonObject()
              .put("inventory", new JsonObject()
                  .put("isbn", new JsonArray().add("73209622")));
          Set<String> keys = new HashSet<>();
          m.getKeys(payload, keys);
          assertThat(keys, contains("73209622"));

          payload = new JsonObject()
              .put("inventory", new JsonObject()
                  .put("issn", new JsonArray().add("73209622")));
          keys.clear();
          m.getKeys(payload, keys);
          assertThat(keys, is(empty()));
        }));
  }

  Future<Void> matchKeyVerify(String pattern, Set<String> expectedKeys, JsonObject payload) {
    MatchKeyMethod m = new MatchKeyJsonPath();
    return m.configure(vertx, new JsonObject().put("expr", pattern))
        .map(matchKeyMethod -> {
          Set<String> keys = new HashSet<>();
          m.getKeys(payload, keys);
          Assert.assertEquals(expectedKeys, keys);
          return null;
        });
  }

  @Test
  public void matchKeyJsonPathExpressions(TestContext context) {
    JsonObject inventory = new JsonObject()
        .put("identifiers", new JsonArray()
            .add(new JsonObject()
                .put("isbn", "73209629"))
            .add(new JsonObject()
                .put("isbn", "73209623"))

        )
        .put("matchKey", new JsonObject()
            .put("title", "Panisci fistula")
            .put("remainder-of-title", " : tre preludi per tre flauti")
            .put("medium", "[sound recording]")
        )
        ;

    matchKeyVerify("$.identifiers[*].isbn", Set.of("73209629", "73209623"), inventory)
        .compose(x -> matchKeyVerify("$.matchKey.title", Set.of("Panisci fistula"), inventory))
        .compose(x -> matchKeyVerify("$.matchKey", Set.of(), inventory))
        .compose(x -> matchKeyVerify("$.matchKey[?(@.title)]", Set.of(), inventory))
        .onComplete(context.asyncAssertSuccess());
  }

}
