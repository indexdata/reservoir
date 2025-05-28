package org.folio.reservoir.util.readstream;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@RunWith(VertxUnitRunner.class)
public class MarcJsonToIngestTest {
  Vertx vertx;

  @Before
  public void before() {
    vertx = Vertx.vertx();
  }

  @After
  public void after(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  Future<ReadStream<JsonObject>> marcFromFile(String fname) {
    return vertx.fileSystem()
        .open(fname, new OpenOptions())
        .map(file -> new MappingReadStream<>(new MarcToJsonParser(file),
            new MarcJsonToIngestMapper())
            .pause());
  }

  String get001(JsonObject marc) {
    return marc.getJsonArray("fields").getJsonObject(0).getString("001");
  }

  @Test
  public void marc3(TestContext context) {
    marcFromFile("marc3.marc")
        .compose(parser -> {
          List<JsonObject> records = new ArrayList<>();
          Promise<List<JsonObject>> promise = Promise.promise();
          parser.handler(records::add);
          parser.endHandler(e -> promise.complete(records));
          parser.exceptionHandler(promise::tryFail);
          parser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertSuccess(records -> {
          assertThat(records, hasSize(3));
          assertThat(records.get(0).getString("localId"), is("73209622 //r823"));
          assertThat(get001(records.get(0).getJsonObject("payload").getJsonObject("marc")), is("   73209622 //r823"));
          assertThat(records.get(0).containsKey("marcHoldings"), is(false));
          assertThat(records.get(1).getString("localId"), is("11224466"));
          assertThat(get001(records.get(1).getJsonObject("payload").getJsonObject("marc")), is("   11224466 "));
          assertThat(records.get(2).getString("localId"), is("77123332"));
          assertThat(get001(records.get(2).getJsonObject("payload").getJsonObject("marc")), is("   77123332 "));
        }));
  }

  @Test
  public void mfhd(TestContext context) {
    marcFromFile("mfhd.marc")
        .compose(parser -> {
          List<JsonObject> records = new ArrayList<>();
          Promise<List<JsonObject>> promise = Promise.promise();
          parser.handler(records::add);
          parser.endHandler(e -> promise.complete(records));
          parser.exceptionHandler(promise::tryFail);
          parser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertSuccess(records -> {
          assertThat(records, hasSize(7));
          assertThat(records.get(0).getString("localId"), is("ocm22544415"));
          assertThat(get001(records.get(0).getJsonObject("payload").getJsonObject("marc")), is("ocm22544415 "));
          assertThat(get001(records.get(0).getJsonObject("payload").getJsonArray("marcHoldings").getJsonObject(0)), is("203780362"));
          assertThat(records.get(6).getString("localId"), is("on1328230635"));
          assertThat(get001(records.get(6).getJsonObject("payload").getJsonObject("marc")), is("on1328230635"));
          assertThat(get001(records.get(6).getJsonObject("payload").getJsonArray("marcHoldings").getJsonObject(0)), is("385266588"));
        }));
  }

  @Test
  public void parentMarcIsHolding(TestContext context) {
    marcFromFile("mfhd-no-parent.marc")
        .compose(parser -> {
          List<JsonObject> records = new ArrayList<>();
          Promise<List<JsonObject>> promise = Promise.promise();
          parser.handler(records::add);
          parser.endHandler(e -> promise.complete(records));
          parser.exceptionHandler(promise::tryFail);
          parser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), startsWith("Parent MARC record is holding"))
        ));
  }

  @Test
  public void testgetLocalIdFromMarc() {
    assertThat(MarcJsonToIngestMapper.getLocalId(new JsonObject()), nullValue());
    assertThat(MarcJsonToIngestMapper.getLocalId(new JsonObject().put("fields", new JsonArray())), nullValue());
    {
      JsonObject t = new JsonObject().put("fields", "2");
      Assert.assertThrows(ClassCastException.class, () -> MarcJsonToIngestMapper.getLocalId(t));
    }
    {
      JsonObject t = new JsonObject().put("fields", new JsonArray().add("1"));
      Assert.assertThrows(ClassCastException.class, () -> MarcJsonToIngestMapper.getLocalId(t));
    }
    {
      JsonObject t = new JsonObject().put("fields", new JsonArray().add(new JsonObject().put("002", "3")));
      assertThat(MarcJsonToIngestMapper.getLocalId(t), nullValue());
    }
    {
      JsonObject t = new JsonObject().put("fields", new JsonArray().add(new JsonObject().put("001", null)));
      assertThat(MarcJsonToIngestMapper.getLocalId(t), nullValue());
    }
    {
      JsonObject t = new JsonObject().put("fields", new JsonArray().add(new JsonObject().put("001", "12 34 ")));
      assertThat(MarcJsonToIngestMapper.getLocalId(t), is("12 34 "));
    }
  }

}
