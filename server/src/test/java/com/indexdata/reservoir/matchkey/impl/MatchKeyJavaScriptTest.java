package org.folio.reservoir.matchkey.impl;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import io.vertx.reactivex.core.http.HttpHeaders;
import java.util.Collection;
import java.util.HashSet;

import org.folio.reservoir.matchkey.MatchKeyMethod;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

@RunWith(VertxUnitRunner.class)
public class MatchKeyJavaScriptTest {

  static Vertx vertx;

  static HttpServer httpServer;

  static int PORT = 9230;

  static String HOSTPORT = "http://localhost:" + PORT;

  @BeforeClass
  public static void beforeClass(TestContext context)  {
    vertx = Vertx.vertx();
    Router router = Router.router(vertx);
    router.get("/lib/mult.js").handler(ctx -> {
      HttpServerResponse response = ctx.response();
      response.setStatusCode(200);
      response.putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
      response.end("function mult(p1, p2) { return p1 * p2; }");
    });
    router.get("/lib/syntaxerror.js").handler(ctx -> {
      HttpServerResponse response = ctx.response();
      response.setStatusCode(200);
      response.putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
      response.end("function mult(p1, p2) { return p1 *; }");
    });
    router.get("/lib/isbn-match.js").handler(ctx -> {
      HttpServerResponse response = ctx.response();
      response.setStatusCode(200);
      response.putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
      response.end("x => {"
          + "var identifiers = JSON.parse(x).identifiers;"
          + "const isbn = [];"
          + "for (i = 0; i < identifiers.length; i++) {"
          + "  isbn.push(identifiers[i].isbn);"
          + "}"
          + "return isbn;"
          + "}");
    });
    router.get("/lib/isbn-match.mjs").handler(ctx -> {
      HttpServerResponse response = ctx.response();
      response.setStatusCode(200);
      response.putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
      response.end("export function matchkey(x) {"
          + "var identifiers = JSON.parse(x).identifiers;"
          + "const isbn = [];"
          + "for (let i = 0; i < identifiers.length; i++) {"
          + "  isbn.push(identifiers[i].isbn);"
          + "}"
          + "return isbn;"
          + "}");
    });
    httpServer = vertx.createHttpServer();
    httpServer.requestHandler(router).listen(PORT).onComplete(context.asyncAssertSuccess());
  }

  @AfterClass
  public static void afterClass(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testMissingConfig(TestContext context) {
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject())
        .onComplete(context.asyncAssertFailure(e ->
          assertThat(e.getMessage(), is("javascript: url or script must be given"))
        ));
  }

  @Test
  public void testBadJavaScript(TestContext context) {
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject().put("script", "x =>"))
        .onComplete(context.asyncAssertFailure(e ->
            assertThat(e.getMessage(), containsString("Expected an operand but found eof"))
        ));
  }

  @Test
  public void testLong(TestContext context) {
    Collection<String> keys = new HashSet<>();
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject()
            .put("script", "x => JSON.parse(x).id + 1"))
        .onComplete(context.asyncAssertSuccess(x -> {
          m.getKeys(new JsonObject().put("id", 2), keys);
          assertThat(keys, containsInAnyOrder("3"));
        }));
  }

  @Test
  public void testString(TestContext context) {
    Collection<String> keys = new HashSet<>();
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject()
            .put("script", "x => JSON.parse(x).id + 'x'"))
        .onComplete(context.asyncAssertSuccess(x -> {
          m.getKeys(new JsonObject().put("id", "2"), keys);
          assertThat(keys, containsInAnyOrder("2x"));
        }));
  }

  @Test
  public void testBoolean(TestContext context) {
    Collection<String> keys = new HashSet<>();
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject()
            .put("script", "x => JSON.parse(x).id > 1"))
        .onComplete(context.asyncAssertSuccess(x -> {
          m.getKeys(new JsonObject().put("id", "2"), keys);
          assertThat(keys, containsInAnyOrder());
        }));
  }

  @Test
  public void testArray(TestContext context) {
    Collection<String> keys = new HashSet<>();
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject()
            .put("script", "function mult(p1, p2) { return p1 * p2; };"
            + " x => [JSON.parse(x).id, mult(2, 3)]"))
        .onComplete(context.asyncAssertSuccess(x -> {
          m.getKeys(new JsonObject().put("id", "2"), keys);
          assertThat(keys, containsInAnyOrder("6", "2"));
        }));
  }

  @Test
  public void testNotFound(TestContext context) {
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject()
            .put("url", HOSTPORT + "/lib/notfound.js"))
        .onComplete(context.asyncAssertFailure(e ->
            assertThat(e.getMessage(), is("Response status code 404 is not equal to 200"))
        ));
  }

  @Test
  public void testSyntaxError(TestContext context) {
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject()
            .put("url", HOSTPORT + "/lib/syntaxerror.js"))
        .onComplete(context.asyncAssertFailure(e -> {
          assertThat(e.getMessage(), containsString("SyntaxError"));
          m.close();
        }));
  }

  @Test
  public void testIsbnMatchUrl(TestContext context) {
    JsonObject inventory = new JsonObject()
        .put("identifiers", new JsonArray()
            .add(new JsonObject()
                .put("isbn", "73209629"))
            .add(new JsonObject()
                .put("isbn", "73209623"))

        );
    Collection<String> keys = new HashSet<>();
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject()
            .put("url", HOSTPORT + "/lib/isbn-match.js"))
        .onComplete(context.asyncAssertSuccess(x -> {
          m.getKeys(inventory, keys);
          assertThat(keys, containsInAnyOrder("73209629", "73209623"));
          m.close();
          m.close();
        }));
  }

  @Test
  public void testIsbnMatchUrlModule(TestContext context) {
    JsonObject inventory = new JsonObject()
        .put("identifiers", new JsonArray()
            .add(new JsonObject()
                .put("isbn", "73209629"))
            .add(new JsonObject()
                .put("isbn", "73209623"))

        );
    Collection<String> keys = new HashSet<>();
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject()
            .put("url", HOSTPORT + "/lib/isbn-match.mjs"))
        .onComplete(context.asyncAssertSuccess(x -> {
          m.getKeys(inventory, keys);
          assertThat(keys, containsInAnyOrder("73209629", "73209623"));
          m.close();
          m.close();
        }));
  }

  @Test
  public void testUrlAndScript(TestContext context) {
    Collection<String> keys = new HashSet<>();
    MatchKeyMethod m = new MatchKeyJavaScript();
    m.configure(vertx, new JsonObject()
            .put("url", HOSTPORT + "/lib/mult.js")
            .put("script", "x => [JSON.parse(x).id, mult(2, 3)]"))
        .onComplete(context.asyncAssertSuccess(x -> {
          m.getKeys(new JsonObject().put("id", "2"), keys);
          assertThat(keys, containsInAnyOrder("6", "2"));
          m.close();
        }));
  }
}
