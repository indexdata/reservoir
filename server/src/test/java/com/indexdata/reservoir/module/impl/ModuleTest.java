package com.indexdata.reservoir.module.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.indexdata.reservoir.module.ModuleCache;
import com.indexdata.reservoir.module.ModuleExecutable;
import com.indexdata.reservoir.module.ModuleInvocation;
import com.indexdata.reservoir.server.entity.ClusterBuilder;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import com.indexdata.reservoir.server.entity.CodeModuleEntity.CodeModuleBuilder;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import org.graalvm.polyglot.PolyglotException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class ModuleTest {

  static Vertx vertx;

  static int PORT = 9231;

  static String HOSTPORT = "http://localhost:" + PORT;

  static String TENANT = "test";

  @BeforeClass
  public static void beforeClass(TestContext context)  {
    vertx = Vertx.vertx();
    ModuleScripts.serveModules(vertx, PORT).onComplete(context.asyncAssertSuccess());
  }

  @AfterClass
  public static void afterClass(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  @After
  public void before() {
    ModuleCache.getInstance().purgeAll();
  }

  JsonArray recordsIn = new JsonArray()
      //first record
      .add(new JsonObject()
          .put("globalId", "source-1-record-1")
          .put("localId", "REC:A")
          .put("sourceId", "source-1")
          .put("payload", new JsonObject()
              .put("marc", new JsonObject()
                  .put("leader", "leader-1")
                  .put("fields", new JsonArray()
                      .add(new JsonObject()
                          .put("245", new JsonObject()
                              .put("subfields", new JsonArray()
                                  .add(new JsonObject().put("a", "source-1 title"))
                              )
                          )
                      )
                      .add(new JsonObject()
                          .put("998", new JsonObject()
                              .put("subfields", new JsonArray()
                                  .add(new JsonObject().put("x", "source-1 location"))
                              )
                          )
                      )
                  )
              )
          )
      )
      //second record
      .add(new JsonObject()
          .put("globalId", "source-2-record-2")
          .put("localId", "rec_1")
          .put("sourceId", "source-2")
          .put("payload", new JsonObject()
              .put("marc", new JsonObject()
                  .put("leader", "leader-1")
                  .put("fields", new JsonArray()
                      .add(new JsonObject()
                          .put("245", new JsonObject()
                              .put("subfields", new JsonArray()
                                  .add(new JsonObject().put("a", "source-2 title"))
                              )
                          )
                      )
                      .add(new JsonObject()
                          .put("998", new JsonObject()
                              .put("subfields", new JsonArray()
                                  .add(new JsonObject().put("x", "source-2 location"))
                              )
                          )
                      )
                  )
              )
          )
      );

  @Test
  public void testIsbnTransformerUrl(TestContext context) {
      JsonObject recordOut = new JsonObject()
      //merged record
        .put("leader", "new leader")
        .put("fields", new JsonArray()
          .add(new JsonObject()
            .put("245", new JsonObject()
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("a", "source-1 title"))
              )
            )
          )
          .add(new JsonObject()
            .put("998", new JsonObject()
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("x", "source-1 location"))
              )
            )
          )
          .add(new JsonObject()
            .put("999", new JsonObject()
              .put("ind1", "1")
              .put("ind2", "0")
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("i", "source-1-record-1"))
                .add(new JsonObject().put("l", "REC:A"))
                .add(new JsonObject().put("s", "source-1"))
              )
            )
          )
          .add(new JsonObject()
            .put("245", new JsonObject()
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("a", "source-2 title"))
              )
            )
          )
          .add(new JsonObject()
            .put("998", new JsonObject()
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("x", "source-2 location"))
              )
            )
          )
          .add(new JsonObject()
            .put("999", new JsonObject()
              .put("ind1", "1")
              .put("ind2", "0")
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("i", "source-2-record-2"))
                .add(new JsonObject().put("l", "rec_1"))
                .add(new JsonObject().put("s", "source-2"))
              )
            )
          )
        );

    ClusterBuilder cb = new ClusterBuilder(UUID.randomUUID());
    cb.records(recordsIn);
    JsonObject input = cb.build();

    JsonObject config = new JsonObject()
      .put("id", "marc-transformer")
      .put("url", HOSTPORT + "/lib/marc-transformer.mjs")
      .put("function", "transform");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity)
        .compose(m -> m.execute(null, input).eventually(() -> m.terminate()))
        .onComplete(context.asyncAssertSuccess(output -> context.assertEquals(recordOut, output)))
    );
  }

  @Test
  public void testIsbnTransformerUrNoFunction(TestContext context) {

    JsonObject config = new JsonObject()
      .put("id", "marc-transformer")
      .put("url", HOSTPORT + "/lib/marc-transformer.mjs");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity)
        .onComplete(context.asyncAssertFailure(e ->
          assertThat(e.getMessage(), is(
          "JS url modules require 'function' defined in config or by caller")))
    ));
  }

  @Test
  public void testIsbnTransformerUrInvocation(TestContext context) {
      JsonObject recordOut = new JsonObject()
      //merged record
        .put("leader", "new leader")
        .put("fields", new JsonArray()
          .add(new JsonObject()
            .put("245", new JsonObject()
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("a", "source-1 title"))
              )
            )
          )
          .add(new JsonObject()
            .put("998", new JsonObject()
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("x", "source-1 location"))
              )
            )
          )
          .add(new JsonObject()
            .put("999", new JsonObject()
              .put("ind1", "1")
              .put("ind2", "0")
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("i", "source-1-record-1"))
                .add(new JsonObject().put("l", "REC:A"))
                .add(new JsonObject().put("s", "source-1"))
              )
            )
          )
          .add(new JsonObject()
            .put("245", new JsonObject()
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("a", "source-2 title"))
              )
            )
          )
          .add(new JsonObject()
            .put("998", new JsonObject()
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("x", "source-2 location"))
              )
            )
          )
          .add(new JsonObject()
            .put("999", new JsonObject()
              .put("ind1", "1")
              .put("ind2", "0")
              .put("subfields", new JsonArray()
                .add(new JsonObject().put("i", "source-2-record-2"))
                .add(new JsonObject().put("l", "rec_1"))
                .add(new JsonObject().put("s", "source-2"))
              )
            )
          )
        );

    ClusterBuilder cb = new ClusterBuilder(UUID.randomUUID());
    cb.records(recordsIn);
    JsonObject input = cb.build();

    JsonObject config = new JsonObject()
      .put("id", "marc-transformer")
      .put("url", HOSTPORT + "/lib/marc-transformer.mjs");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity)
        .compose(m -> new ModuleExecutable(m, new ModuleInvocation("marc-transformer::transform"))
         .execute(input).eventually(() -> m.terminate()))
      .onComplete(context.asyncAssertSuccess(output -> context.assertEquals(recordOut, output))
    ));
  }

  @Test
  public void moduleReturnInt(TestContext context) {
    ClusterBuilder cb = new ClusterBuilder(UUID.randomUUID());
    cb.records(recordsIn);
    JsonObject input = cb.build();

    JsonObject config = new JsonObject()
        .put("id", "returns-int")
        .put("url", HOSTPORT + "/lib/returns-int.mjs")
        .put("function", "transform");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity)
        .compose(m -> m.execute(null, input).eventually(() -> m.terminate()))
        .onComplete(context.asyncAssertFailure(
            e -> {
              assertThat(e.getClass(), is(IllegalArgumentException.class));
              assertThat(e.getMessage(), containsString("must return JSON string"));
            }
      )));
  }

  @Test
  public void moduleThrowsException(TestContext context) {
    ClusterBuilder cb = new ClusterBuilder(UUID.randomUUID());
    cb.records(recordsIn);
    JsonObject input = cb.build();

    JsonObject config = new JsonObject()
        .put("id", "throw")
        .put("url", HOSTPORT + "/lib/throw.mjs")
        .put("function", "transform");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity)
        .compose(m -> m.execute(null, input).eventually(() -> m.terminate()))
        .onComplete(context.asyncAssertFailure(
            e -> {
              assertThat(e.getClass(), is(PolyglotException.class));
              assertThat(e.getMessage(), is("Error"));
            })));
  }

  @Test
  public void moduleBadJson(TestContext context) {
    ClusterBuilder cb = new ClusterBuilder(UUID.randomUUID());
    cb.records(recordsIn);
    JsonObject input = cb.build();

    JsonObject config = new JsonObject()
        .put("id", "bad-json")
        .put("url", HOSTPORT + "/lib/bad-json.mjs")
        .put("function", "transform");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
    ModuleCache.getInstance().lookup(vertx, TENANT, entity)
        .compose(m -> m.execute(null, input).eventually(() -> m.terminate()))
        .onComplete(context.asyncAssertFailure(
            e -> {
              assertThat(e.getClass(), is(DecodeException.class));
              assertThat(e.getMessage(), containsString("Unexpected end-of-input"));
            })));
  }

  @Test
  public void cachingEquals(TestContext context) {
    JsonObject config = new JsonObject()
        .put("id", "marc-transformer")
        .put("url", HOSTPORT + "/lib/marc-transformer.mjs")
        .put("function", "transform");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity).compose(m1 ->
        ModuleCache.getInstance().lookup(vertx, TENANT, entity).map(m2 -> m1 == m2))
          .onComplete(context.asyncAssertSuccess(equals -> context.assertTrue(equals))
      )
    );
  }

  @Test
  public void cachingPurge(TestContext context) {
    JsonObject config = new JsonObject()
        .put("id", "marc-transformer")
        .put("url", HOSTPORT + "/lib/marc-transformer.mjs")
        .put("function", "transform");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity).compose(m1 -> {
        ModuleCache.getInstance().purge(TENANT, "marc-transformer");
        ModuleCache.getInstance().purge(TENANT, "marc-transformer");
        return ModuleCache.getInstance().lookup(vertx, TENANT, entity).map(m2 -> m1 == m2);
      })
      .onComplete(context.asyncAssertSuccess(equals -> context.assertFalse(equals)))
    );
  }

  @Test
  public void cachingNotEquals(TestContext context) {
    JsonObject config = new JsonObject()
        .put("id", "marc-transformer1")
        .put("url", HOSTPORT + "/lib/marc-transformer.mjs")
        .put("function", "transform");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
    ModuleCache.getInstance().lookup(vertx, TENANT, entity).compose(m1 -> {
      config.put("id", "marc-transformer2");
      return new CodeModuleBuilder(config).resolve(vertx).compose(entity2 ->
        ModuleCache.getInstance().lookup(vertx, TENANT, entity2).map(m2 -> m1 == m2));
      })
      .onComplete(context.asyncAssertSuccess(equals -> context.assertFalse(equals)))
    );
  }

  @Test
  public void cachingChangedConfig(TestContext context) {
    JsonObject config1 = new JsonObject()
        .put("id", "marc-transformer")
        .put("url", HOSTPORT + "/lib/marc-transformer.mjs")
        .put("function", "transform");

    JsonObject config2 = new JsonObject()
        .put("id", "marc-transformer")
        .put("url", HOSTPORT + "/lib/marc-transformer.mjs")
        .put("function", "transform1");

    new CodeModuleBuilder(config1).resolve(vertx).compose(entity1 ->
      new CodeModuleBuilder(config2).resolve(vertx).compose(entity2 ->
        ModuleCache.getInstance().lookup(vertx, TENANT, entity1).compose(m1 ->
          ModuleCache.getInstance().lookup(vertx, TENANT, entity2).map(m2 -> m1 == m2))
            .onComplete(context.asyncAssertSuccess(equals -> context.assertFalse(equals)))));
  }

  @Test
  public void exceptionInInitialize(TestContext context) {
    JsonObject config1 = new JsonObject()
        .put("id", "marc-transformer")
        .put("url", HOSTPORT + "/lib/marc-transformer.mjs")
        .put("function", "transform1");

    new CodeModuleBuilder(config1).resolve(vertx).compose(entity1 ->
    ModuleCache.getInstance().lookup(vertx, TENANT, entity1)
        .compose(m -> m.execute(null, null).eventually(() -> m.terminate()))
        .onComplete(context.asyncAssertFailure(e ->
            assertThat(e.getMessage(), containsString("does not include function transform1")))));
  }

  @Test
  public void mustEndInMjs(TestContext context) {
    JsonObject config1 = new JsonObject()
        .put("id", "marc-transformer")
        .put("url", HOSTPORT + "/lib/marc-transformer.js")
        .put("function", "transform1");

    new CodeModuleBuilder(config1).resolve(vertx).compose(entity1 ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity1)
        .onComplete(context.asyncAssertFailure(e ->
            assertThat(e.getMessage(), is("url must end with .mjs to designate ES module")))));
  }

  @Test
  public void missingUrl(TestContext context) {
    JsonObject config1 = new JsonObject()
        .put("id", "marc-transformer")
        .put("function", "transform");

    new CodeModuleBuilder(config1).resolve(vertx).compose(entity1 ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity1)
        .onComplete(context.asyncAssertFailure(e ->
            assertThat(e.getMessage(), is("Module config must include 'url' or 'script'")))));
  }

  @Test
  public void missingFunction(TestContext context) {
    JsonObject config1 = new JsonObject()
        .put("id", "marc-transformer")
        .put("url", HOSTPORT + "/lib/marc-transformer.mjs");

    new CodeModuleBuilder(config1).resolve(vertx).compose(entity1 ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity1)
        .onComplete(context.asyncAssertSuccess()));
  }

  @Test
  public void missingModuleId(TestContext context) {
    JsonObject config1 = new JsonObject()
        .put("url", HOSTPORT + "/lib/marc-transformer.mjs")
        .put("function", "transform");

    new CodeModuleBuilder(config1).resolve(vertx).compose(entity1 ->
      ModuleCache.getInstance().lookup(vertx, TENANT, entity1)
        .onComplete(context.asyncAssertFailure(e ->
            assertThat(e.getMessage(), is("module config must include 'id'")))));
  }

  @Test
  public void moduleSourceNotFound(TestContext context) {
    String url = HOSTPORT + "/lib/not-found.mjs";
    JsonObject config1 = new JsonObject()
        .put("id", "marc-transformer")
        .put("url", url)
        .put("function", "transform");

    new CodeModuleBuilder(config1).resolve(vertx)
        .onComplete(context.asyncAssertFailure(e ->
            assertThat(e.getMessage(),
            is("Config error: cannot retrieve module 'marc-transformer' at " + url + " (404)"))));
  }

  @Test
  public void testJsonPathModule(TestContext context) {
    Collection<String> expected = new HashSet<>();
    expected.add("source-1 title");

    JsonObject payload = new JsonObject()
        .put("marc", new JsonObject()
          .put("leader", "leader-1")
          .put("fields", new JsonArray()
              .add(new JsonObject()
                  .put("245", new JsonObject()
                      .put("subfields", new JsonArray()
                          .add(new JsonObject().put("a", "source-1 title"))
                      )
                  )
              )
              .add(new JsonObject()
                  .put("998", new JsonObject()
                      .put("subfields", new JsonArray()
                          .add(new JsonObject().put("x", "source-1 location"))
                      )
                  )
              )
          )
      );

    JsonObject config = new JsonObject()
      .put("id", "matchkey-title")
      .put("type", "jsonpath")
      .put("script", "$.marc.fields[*].245.subfields[*].a");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
    ModuleCache.getInstance()
      .lookup(vertx, TENANT, entity)
      .map(m -> m.executeAsCollection(null, payload))
      .onComplete(context.asyncAssertSuccess(result -> context.assertEquals(expected, result.result())))
    );
  }

  @Test
  public void testInlineJsMatchkeyModule(TestContext context) {
    Collection<String> expected = new HashSet<>();
    expected.add("73209629");
    expected.add("73209623");

    JsonObject payload = new JsonObject()
        .put("identifiers", new JsonArray()
            .add(new JsonObject()
                .put("isbn", "73209629"))
            .add(new JsonObject()
                .put("isbn", "73209623"))
        );

    JsonObject config = new JsonObject()
      .put("id", "matchkey-isbn")
      .put("type", "javascript")
      .put("script", "x => {"
      + "var identifiers = JSON.parse(x).identifiers;"
      + "const isbn = [];"
      + "for (i = 0; i < identifiers.length; i++) {"
      + "  isbn.push(identifiers[i].isbn);"
      + "}"
      + "return isbn;"
      + "}");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance()
        .lookup(vertx, TENANT, entity)
        .compose(m -> m.executeAsCollection(null, payload))
        .onComplete(context.asyncAssertSuccess(result -> {
          context.assertEquals(expected, result);
        })
    ));
  }

  @Test
  public void testJsMatchkeyModuleNoFunction(TestContext context) {
    JsonObject config = new JsonObject()
      .put("id", "matchkey-isbn")
      .put("type", "javascript")
      .put("url", HOSTPORT + "/lib/matchkey-isbn.mjs");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance()
        .lookup(vertx, TENANT, entity)
        .compose(m -> m.executeAsCollection(null, new JsonObject()))
        .onComplete(context.asyncAssertFailure(e ->
          assertThat(e.getMessage(), is("JS url modules require 'function' defined in config or by caller"))))
      );
  }

  @Test
  public void testJsMatchkeyModuleFunctionInConfig(TestContext context) {
    Collection<String> expected = new HashSet<>();
    expected.add("73209629");
    expected.add("73209623");

    JsonObject payload = new JsonObject()
        .put("identifiers", new JsonArray()
            .add(new JsonObject()
                .put("isbn", "73209629"))
            .add(new JsonObject()
                .put("isbn", "73209623"))
        );

    JsonObject config = new JsonObject()
      .put("id", "matchkey-isbn")
      .put("type", "javascript")
      .put("url", HOSTPORT + "/lib/matchkey-isbn.mjs")
      .put("function", "matchkey");


    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
    ModuleCache.getInstance()
      .lookup(vertx, TENANT, entity)
      .compose(m -> m.executeAsCollection(null, payload))
      .onComplete(context.asyncAssertSuccess(result -> context.assertEquals(expected, result)))
    );
  }

  @Test
  public void testJsMatchkeyModuleFunctionByCaller(TestContext context) {
    Collection<String> expected = new HashSet<>();
    expected.add("73209629");
    expected.add("73209623");

    JsonObject payload = new JsonObject()
        .put("identifiers", new JsonArray()
            .add(new JsonObject()
                .put("isbn", "73209629"))
            .add(new JsonObject()
                .put("isbn", "73209623"))
        );

    JsonObject config = new JsonObject()
      .put("id", "matchkey-isbn")
      .put("type", "javascript")
      .put("url", HOSTPORT + "/lib/matchkey-isbn.mjs");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance()
        .lookup(vertx, TENANT, entity)
        .compose(m -> m.executeAsCollection("matchkey", payload))
        .onComplete(context.asyncAssertSuccess(result -> context.assertEquals(expected, result)))
    );
  }

  @Test
  public void testJsMatchkeyModuleFunctionByCallerInvocation(TestContext context) {
    Collection<String> expected = new HashSet<>();
    expected.add("73209629");
    expected.add("73209623");

    JsonObject payload = new JsonObject()
        .put("identifiers", new JsonArray()
            .add(new JsonObject()
                .put("isbn", "73209629"))
            .add(new JsonObject()
                .put("isbn", "73209623"))
        );

    JsonObject config = new JsonObject()
      .put("id", "matchkey-isbn")
      .put("type", "javascript")
      .put("url", HOSTPORT + "/lib/matchkey-isbn.mjs");

    new CodeModuleBuilder(config).resolve(vertx).compose(entity ->
      ModuleCache.getInstance()
        .lookup(vertx, TENANT, entity)
        .compose(m -> new ModuleExecutable(m, new ModuleInvocation("matchkey-isbn::matchkey")).executeAsCollection(payload))
        .onComplete(context.asyncAssertSuccess(result -> context.assertEquals(expected, result)))
    );
  }

}
