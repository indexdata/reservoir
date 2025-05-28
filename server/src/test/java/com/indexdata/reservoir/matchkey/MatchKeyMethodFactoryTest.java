package org.folio.reservoir.matchkey;

import com.jayway.jsonpath.InvalidPathException;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.folio.okapi.testing.UtilityClassTester;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@RunWith(VertxUnitRunner.class)
public class MatchKeyMethodFactoryTest {
  static final String TENANT = "tenant";

  static final String MATCHKEYID = "matchkeyid";
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
  public void isUtilityClass() {
    UtilityClassTester.assertUtilityClass(MatchKeyMethodFactory.class);
  }

  @Test
  public void matchKeyJsonPathNonConfigured(TestContext context) {
    MatchKeyMethodFactory.get(vertx, TENANT, MATCHKEYID, "foo", new JsonObject()).onComplete(context.asyncAssertFailure(e ->
        assertThat(e.getMessage(), Matchers.is("Unknown match key method foo"))
    ));
  }

  @Test
  public void testSameConfig(TestContext context) {
    MatchKeyMethodFactory.clearCache();
    MatchKeyMethodFactory.get(vertx, TENANT, MATCHKEYID, "javascript", new JsonObject()
        .put("script", "x => JSON.parse(x).id + 'x'")).compose(m1 ->
        MatchKeyMethod.get(vertx, TENANT, MATCHKEYID, "javascript", new JsonObject()
                .put("script", "x => JSON.parse(x).id + 'x'"))
            .onComplete(context.asyncAssertSuccess(m2 -> {
              assertThat(m1, is(m2));
              assertThat(MatchKeyMethodFactory.getCacheSize(), is(1));
            })));
  }

  @Test
  public void testDiffConfig(TestContext context) {
    MatchKeyMethodFactory.clearCache();
    MatchKeyMethodFactory.get(vertx, TENANT, MATCHKEYID, "javascript", new JsonObject()
        .put("script", "x => JSON.parse(x).id + 'x'")).compose(m1 ->
        MatchKeyMethodFactory.get(vertx, TENANT, MATCHKEYID, "javascript", new JsonObject()
                .put("script", "x => JSON.parse(x).id + 'y'"))
            .onComplete(context.asyncAssertSuccess(m2 -> {
              assertThat(m1, not(m2));
              assertThat(MatchKeyMethodFactory.getCacheSize(), is(1));
            })));
  }

  @Test
  public void testDiffKey(TestContext context) {
    MatchKeyMethodFactory.clearCache();
    MatchKeyMethodFactory.get(vertx, TENANT, "key1", "javascript", new JsonObject()
        .put("script", "x => JSON.parse(x).id + 'x'")).compose(m1 ->
        MatchKeyMethodFactory.get(vertx, TENANT, "key2", "javascript", new JsonObject()
                .put("script", "x => JSON.parse(x).id + 'x'"))
            .onComplete(context.asyncAssertSuccess(m2 -> {
              assertThat(m1, not(m2));
              assertThat(MatchKeyMethodFactory.getCacheSize(), is(2));
            })));
  }

  @Test
  public void testDiffTenant(TestContext context) {
    MatchKeyMethodFactory.clearCache();
    MatchKeyMethodFactory.get(vertx, "t1", MATCHKEYID, "javascript", new JsonObject()
        .put("script", "x => JSON.parse(x).id + 'x'")).compose(m1 ->
        MatchKeyMethod.get(vertx, "t2", MATCHKEYID, "javascript", new JsonObject()
                .put("script", "x => JSON.parse(x).id + 'x'"))
            .onComplete(context.asyncAssertSuccess(m2 -> {
              assertThat(m1, not(m2));
              assertThat(MatchKeyMethodFactory.getCacheSize(), is(2));
            })));
  }

  @Test
  public void matchKeyJsonPathConfigureInvalidJsonPath(TestContext context) {
    JsonObject configuration = new JsonObject().put("expr", "$.fields.010.subfields[x");
    MatchKeyMethodFactory.get(vertx, TENANT, MATCHKEYID, "jsonpath", configuration)
        .onComplete(context.asyncAssertFailure(e ->
            assertThat(e.getClass(), is(InvalidPathException.class))
        ));
  }

}
