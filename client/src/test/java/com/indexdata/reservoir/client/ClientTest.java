package com.indexdata.reservoir.client;

import com.indexdata.reservoir.util.SourceId;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class ClientTest {

  private static final int PORT = 9230;
  Vertx vertx;
  Client client;

  @Before
  public void before() {
    vertx = Vertx.vertx();
    client = new Client(vertx, "http://localhost:" + PORT, null, "testlib");
  }

  @After
  public void before(TestContext context) {
    client.close();
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void noArgs(TestContext context) {
    String [] args = {};
    Main.main(args);
    Client.exec(vertx, args).onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void fileNotFound(TestContext context) {
    String [] args = { "--source", "S1", "unknownfile" };
    Client.exec(vertx, args).onComplete(context.asyncAssertFailure(x -> {
      context.assertEquals("unknownfile (No such file or directory)", x.getMessage());
    }));
  }

  @Test
  public void missingSource(TestContext context) {
    String [] args = { "unknownfile" };
    Client.exec(vertx, args).onComplete(context.asyncAssertFailure(x -> {
      context.assertEquals("source identifier must be given", x.getMessage());
    }));
  }

  @Test
  public void badArgs(TestContext context) {
    String [] args = { "--bad", "value" };
    Client.exec(vertx, args).onComplete(context.asyncAssertFailure(x -> {
      context.assertEquals("Unsupported option: '--bad'", x.getMessage());
    }));
  }

  @Test
  public void missingArgs(TestContext context) {
    String [] args = { "--chunk" };
    Client.exec(vertx, args).onComplete(context.asyncAssertFailure(x -> {
      context.assertEquals("Missing argument for option '--chunk'", x.getMessage());
    }));
  }

  @Test
  public void help(TestContext context) {
    String [] args = { "--help" };
    Client.exec(vertx, args).onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void init(TestContext context) {
    UUID jobId = UUID.randomUUID();

    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);
    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);

    router.post("/_/tenant")
        .handler(BodyHandler.create())
        .handler(c -> {
          if (Boolean.TRUE.equals(c.body().asJsonObject().getBoolean("purge"))) {
            c.response().setStatusCode(204);
            c.response().end();
            return;
          }
          c.response().setStatusCode(201);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end(new JsonObject()
              .put("id", jobId.toString())
              .encode());
        });
    router.getWithRegex("/_/tenant/" + jobId)
        .handler(c -> {
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end(new JsonObject()
              .put("id", jobId.toString())
              .put("complete", Boolean.TRUE)
              .encode());
        });
    router.deleteWithRegex("/_/tenant/" + jobId)
        .handler(c -> {
          c.response().setStatusCode(204);
          c.response().end();
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    String sourceId = "S1";
    String [] args = {
        "--source", sourceId,
        "--purge",
        "--init"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void sendMarcRecFile(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    String sourceId = "S1";
    String [] args = {
        "--chunk", "2",
        "--source", sourceId,
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/marc3.marc"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(res -> {
          context.assertEquals(2, requests.size()); // two requests

          // first chunk with 2 records
          JsonObject r = requests.getJsonObject(0);
          context.assertEquals(sourceId, r.getString("sourceId"));
          context.assertEquals(2, r.getJsonArray("records").size());
          context.assertEquals("   73209622 //r823", r.getJsonArray("records").getJsonObject(0).getString("localId"));
          context.assertEquals("   11224466 ", r.getJsonArray("records").getJsonObject(1).getString("localId"));
          // second with 1 record
          r = requests.getJsonObject(1);
          context.assertEquals(sourceId, r.getString("sourceId"));
          context.assertEquals(1, r.getJsonArray("records").size());
          context.assertEquals("   77123332 ", r.getJsonArray("records").getJsonObject(0).getString("localId"));
        }));
  }

  @Test
  public void sendMarcRecFileServerError(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          c.response().setStatusCode(400);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{\"error\": \"bad request\"}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    SourceId sourceId = new SourceId("source-1");
    String [] args = {
        "--chunk", "2",
        "--source", sourceId.toString(),
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/marc3.marc"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertFailure(e -> {
          context.assertEquals("400 {\"error\": \"bad request\"}", e.getMessage());
        }));
  }

  @Test
  public void sendMarcRecFileBadLeaderPermissive(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    SourceId sourceId = new SourceId("source-1");
    String [] args = {
        "--chunk", "2",
        "--source", sourceId.toString(),
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/badleader.marc"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(res -> {
          context.assertEquals(2, requests.size()); // two requests

          // first chunk with 2 records
          JsonObject r = requests.getJsonObject(0);
          context.assertEquals(sourceId.toString(), r.getString("sourceId"));
          context.assertEquals(2, r.getJsonArray("records").size());
          context.assertEquals("   73209622 //r823", r.getJsonArray("records").getJsonObject(0).getString("localId"));
          context.assertEquals("   11224466 ", r.getJsonArray("records").getJsonObject(1).getString("localId"));
          // second with 1 record
          r = requests.getJsonObject(1);
          context.assertEquals(sourceId.toString(), r.getString("sourceId"));
          context.assertEquals(1, r.getJsonArray("records").size());
          context.assertEquals("   77123332 ", r.getJsonArray("records").getJsonObject(0).getString("localId"));
        }));
  }

  @Test
  public void sendMarcRecFileBadLeaderStrict(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    SourceId sourceId = new SourceId("source-1");
    String [] args = {
        "--chunk", "2",
        "--source", sourceId.toString(),
        "--strict",
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/badleader.marc"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertFailure(e -> {
          context.assertTrue(e.getMessage().startsWith("error parsing leader"));
        }));
  }

  @Test
  public void sendMarcRecFileBadCharStrict(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    SourceId sourceId = new SourceId("source-1");
    String [] args = {
        "--chunk", "2",
        "--source", sourceId.toString(),
        "--strict",
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/badchar.marc"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(e -> {
          context.assertEquals(1, requests.size()); // two requests
        }));
  }

  @Test
  public void sendMarcRecFileBadCharStrictUtf8(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    SourceId sourceId = new SourceId("source-1");
    String [] args = {
        "--chunk", "2",
        "--source", sourceId.toString(),
        "--strict",
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/badchar-utf8.marc"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(e -> {
          context.assertEquals(1, requests.size()); // two requests
        }));
  }

  @Test
  public void sendMarcRecFileBadCharPermissive(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    SourceId sourceId = new SourceId("source-1");
    String [] args = {
        "--chunk", "2",
        "--source", sourceId.toString(),
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/badchar.marc"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(e -> {
          context.assertEquals(1, requests.size()); // two requests
        }));
  }

  @Test
  public void sendMarcRecFileBadCharPermissiveUtf8(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    SourceId sourceId = new SourceId("source-1");
    String [] args = {
        "--chunk", "2",
        "--source", sourceId.toString(),
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/badchar-utf8.marc"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(e -> {
          context.assertEquals(1, requests.size()); // two requests
        }));
  }

  @Test
  public void sendMarcXmlRecords(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    String sourceId = "S1";
    String [] args = {
        "--chunk", "4",
        "--source", sourceId,
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/record10.xml"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(res -> {
          context.assertEquals(3, requests.size());

          // first chunk with 4 records
          JsonObject r = requests.getJsonObject(0);
          context.assertEquals(sourceId, r.getString("sourceId"));
          context.assertEquals(4, r.getJsonArray("records").size());
          // 2nd chunk with 4 records
          r = requests.getJsonObject(1);
          context.assertEquals(sourceId, r.getString("sourceId"));
          context.assertEquals(4, r.getJsonArray("records").size());
          // last chunk with 2 records
          r = requests.getJsonObject(2);
          context.assertEquals(sourceId, r.getString("sourceId"));
          context.assertEquals(2, r.getJsonArray("records").size());
        }));
  }

  @Test
  public void sendMarcXmlRecordsCompressed(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setDecompressionSupported(true)
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();
    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    String sourceId = "S1";
    String [] args = {
        "--chunk", "4",
        "--source", sourceId,
        "--compress",
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/record10.xml"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(res -> {
          context.assertEquals(3, requests.size());

          // first chunk with 4 records
          JsonObject r = requests.getJsonObject(0);
          context.assertEquals(sourceId, r.getString("sourceId"));
          context.assertEquals(4, r.getJsonArray("records").size());
          // 2nd chunk with 4 records
          r = requests.getJsonObject(1);
          context.assertEquals(sourceId, r.getString("sourceId"));
          context.assertEquals(4, r.getJsonArray("records").size());
          // last chunk with 2 records
          r = requests.getJsonObject(2);
          context.assertEquals(sourceId, r.getString("sourceId"));
          context.assertEquals(2, r.getJsonArray("records").size());
        }));
  }

  @Test
  public void sendMarcXmlRecordsHttp2(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setDecompressionSupported(true)
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();
    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    String sourceId = "S1";
    String [] args = {
        "--chunk", "4",
        "--source", sourceId,
        "--http2",
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "src/test/resources/record10.xml"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(res -> {
          context.assertEquals(3, requests.size());

          // first chunk with 4 records
          JsonObject r = requests.getJsonObject(0);
          context.assertEquals(sourceId.toString(), r.getString("sourceId"));
          context.assertEquals(4, r.getJsonArray("records").size());
          // 2nd chunk with 4 records
          r = requests.getJsonObject(1);
          context.assertEquals(sourceId.toString(), r.getString("sourceId"));
          context.assertEquals(4, r.getJsonArray("records").size());
          // last chunk with 2 records
          r = requests.getJsonObject(2);
          context.assertEquals(sourceId.toString(), r.getString("sourceId"));
          context.assertEquals(2, r.getJsonArray("records").size());
        }));
  }

  @Test
  public void offsetLimitMarcXmlRecords(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    String sourceId = "S1";
    String [] args = {
        "--chunk", "4",
        "--source", sourceId.toString(),
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "--offset", "1",
        "--limit", "2",
        "src/test/resources/record10.xml"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(res -> {
          context.assertEquals(1, requests.size());

          // 1 chunk with 2 records
          JsonObject r = requests.getJsonObject(0);
          context.assertEquals(sourceId, r.getString("sourceId"));
          context.assertEquals(2, r.getJsonArray("records").size());

          context.assertEquals("a2", r.getJsonArray("records").getJsonObject(0).getString("localId"));
          context.assertEquals("a3", r.getJsonArray("records").getJsonObject(1).getString("localId"));
        }));
  }

  @Test
  public void echoMarcXmlRecords(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    String sourceId = "S1";
    String [] args = {
        "--chunk", "4",
        "--source", sourceId,
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "--offset", "1",
        "--limit", "2",
        "--echo",
        "src/test/resources/record10.xml"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(res -> {
          context.assertEquals(0, requests.size());
        }));
  }

  @Test
  public void echoOffsetMarcRecords(TestContext context) {
    HttpServerOptions so = new HttpServerOptions()
        .setHandle100ContinueAutomatically(true);

    JsonArray requests = new JsonArray();

    HttpServer httpServer = vertx.createHttpServer(so);
    Router router = Router.router(vertx);
    router.put("/reservoir/records")
        .handler(BodyHandler.create())
        .handler(c -> {
          requests.add(c.body().asJsonObject());
          c.response().setStatusCode(200);
          c.response().putHeader("Content-Type", "application/json");
          c.response().end("{}");
        });

    httpServer.requestHandler(router);
    Future<Void> future = httpServer.listen(PORT).mapEmpty();

    String sourceId = "S1";
    String [] args = {
        "--chunk", "4",
        "--source", sourceId,
        "--xsl", "../xsl/marc2inventory-instance.xsl",
        "--offset", "1",
        "--limit", "2",
        "--echo",
        "src/test/resources/marc3.marc"
    };
    future = future.compose(x -> Client.exec(client, args));

    future.eventually(() -> httpServer.close())
        .onComplete(context.asyncAssertSuccess(res -> {
          context.assertEquals(0, requests.size());
        }));
  }

}
