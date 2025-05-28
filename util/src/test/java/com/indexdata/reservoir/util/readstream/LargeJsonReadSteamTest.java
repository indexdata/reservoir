package org.folio.reservoir.util.readstream;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.reservoir.util.ReadStreamConsumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class LargeJsonReadSteamTest {
  Vertx vertx;
  private final static Logger log = LogManager.getLogger(LargeJsonReadSteamTest.class.getName());

  @Before
  public void before() {
    vertx = Vertx.vertx();
  }

  @After
  public void after(TestContext context) {
    vertx.close(context.asyncAssertSuccess());
  }


  @Test
  public void parse(TestContext context) {
    List<JsonObject> topLevel = new LinkedList<>();
    List<JsonObject> objects = new LinkedList<>();
    AtomicInteger total = new AtomicInteger();
    vertx.fileSystem().open("records-in.json", new OpenOptions())
        .compose(asyncFile -> {
          Promise<Void> p = Promise.promise();
          LargeJsonReadStream jors = new LargeJsonReadStream(asyncFile);
          jors
              .handler(jo -> {
                objects.add(jo);
              })
              .endHandler(x -> {
                total.set(jors.totalCount());
                topLevel.add(jors.topLevelObject());
                asyncFile.close();
                p.complete();
              })
              .exceptionHandler(e -> {
                asyncFile.close();
                p.fail(e);
              });
          return p.future();
        })
        .onComplete(context.asyncAssertSuccess(x ->{
          context.assertEquals(10, total.get());
          context.assertEquals(10, objects.size());
          context.assertEquals("d0166b80-1587-433c-b909-40ccbb1449f6",
              topLevel.get(0).getString("sourceId"));
          for (int i = 0; i < objects.size(); i++) {
            context.assertEquals("a"+(i+1), objects.get(i).getString("localId"));
          }
        }));
  }

  @Test
  public void parseViaConsumer(TestContext context) {
    List<JsonObject> topLevel = new LinkedList<>();
    List<JsonObject> objects = new LinkedList<>();
    vertx.fileSystem().open("records-in.json", new OpenOptions())
        .compose(asyncFile -> {
          LargeJsonReadStream jors = new LargeJsonReadStream(asyncFile);
          return new ReadStreamConsumer<JsonObject, Void>(2)
              .consume(jors, jo -> {
                topLevel.add(jors.topLevelObject());
                objects.add(jo);
                return Future.succeededFuture();
              })
              .compose(x -> asyncFile.close());
        })
        .onComplete(context.asyncAssertSuccess(x ->{
          context.assertEquals(10, objects.size());
          context.assertEquals("d0166b80-1587-433c-b909-40ccbb1449f6",
              topLevel.get(0).getString("sourceId"));
          for (int i = 0; i < objects.size(); i++) {
            context.assertEquals("a"+(i+1), objects.get(i).getString("localId"));
          }
        }));
  }

  @Test
  public void large(TestContext context) {
    List<JsonObject> topLevel = new LinkedList<>();
    AtomicInteger total = new AtomicInteger();
    int number = 1000000; // 1 million
    Buffer preBuffer = Buffer.buffer("{ \"sourceId\": \"d0166b80-1587-433c-b909-40ccbb1449f6\", \"records\" : [");
    Buffer repeatBuffer = Buffer.buffer(new JsonObject()
        .put("localId", "1234")
        .put("payload", new JsonObject().put("leader", "01010ccm a2200289   4500")).encode());

    Buffer sepBuffer = Buffer.buffer(",");
    Buffer postBuffer = Buffer.buffer("]}");
    MemoryReadStream s = new MemoryReadStream(preBuffer, repeatBuffer, sepBuffer, postBuffer, number, vertx);
    Promise<Void> p = Promise.promise();
    LargeJsonReadStream jors = new LargeJsonReadStream(s);
    jors
        .handler(jo -> context.assertEquals("1234", jo.getString("localId")))
        .endHandler(x -> {
          total.set(jors.totalCount());
          topLevel.add(jors.topLevelObject());
          p.complete();
        })
        .exceptionHandler(p::fail);
    s.run();
    p.future().onComplete(context.asyncAssertSuccess(x ->{
      context.assertEquals(number, total.get());
      context.assertEquals("d0166b80-1587-433c-b909-40ccbb1449f6",
          topLevel.get(0).getString("sourceId"));
    }));
  }
}
