package org.folio.reservoir.util.readstream;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@RunWith(VertxUnitRunner.class)
public class MarcXmlParserToJsonTest {
  Vertx vertx;

  @Before
  public void before() {
    vertx = Vertx.vertx();
  }

  @After
  public void after(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  Future<MarcXmlParserToJson> marcXmlParserFromFile(String fname) {
    return vertx.fileSystem().open(fname, new OpenOptions())
        .map(asyncFile -> {
          MarcXmlParserToJson parser = new MarcXmlParserToJson(XmlParser.newParser(asyncFile));
          parser.pause();
          return parser;
        });
  }

  Future<MarcXmlParserToJson> marcXmlParserFromFile() {
    return marcXmlParserFromFile("marc3.xml");
  }

  Future<List<JsonObject>> getRecordsFromFile() {
    List<JsonObject> records = new ArrayList<>();
    return marcXmlParserFromFile().compose(parser -> {
      Promise<List<JsonObject>> promise = Promise.promise();
      parser.handler(records::add);
      parser.endHandler(e -> promise.complete(records));
      parser.exceptionHandler(promise::tryFail);
      parser.resume();
      return promise.future();
    });
  }

  @Test
  public void marc3(TestContext context) {
    getRecordsFromFile()
        .onComplete(context.asyncAssertSuccess(records -> {
          assertThat(records.size(), is(3));
          assertThat(records.get(0).getJsonArray("fields").getJsonObject(0).getString("001"), is("   73209622 //r823"));
          assertThat(records.get(1).getJsonArray("fields").getJsonObject(0).getString("001"), is("   11224466 "));
          assertThat(records.get(2).getJsonArray("fields").getJsonObject(0).getString("001"), is("   77123332 "));
        }));
  }

  @Test
  public void testEndHandler(TestContext context) {
    marcXmlParserFromFile()
        .compose(parser -> {
          Promise<Void> promise = Promise.promise();
          parser.exceptionHandler(promise::tryFail);
          parser.endHandler(x -> promise.tryComplete());
          parser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testEndHandlerExceptionWithExceptionHandler(TestContext context) {
    marcXmlParserFromFile()
        .compose(parser -> {
          Promise<Void> promise = Promise.promise();
          parser.exceptionHandler(promise::tryFail);
          parser.endHandler(x -> {
            throw new RuntimeException("end exception");
          });
          parser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertFailure(e -> assertThat(e.getMessage(), is("end exception"))));
  }

  @Test
  public void testEndHandlerExceptionWithoutExceptionHandler(TestContext context) {
    marcXmlParserFromFile()
        .compose(parser -> {
          Promise<Void> promise = Promise.promise();
          parser.endHandler(x -> {
            promise.tryFail("must stop");
            throw new RuntimeException("end exception");
          });
          parser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertFailure(e -> assertThat(e.getMessage(), is("must stop"))));
  }

  @Test
  public void testHandler(TestContext context) {
    marcXmlParserFromFile()
        .compose(parser -> {
          Promise<Void> promise = Promise.promise();
          AtomicInteger cnt = new AtomicInteger();
          parser.exceptionHandler(promise::tryFail);
          parser.handler(x -> {
            if (cnt.incrementAndGet() == 3) {
              promise.tryComplete();
            }
          });
          parser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testPauseFetch(TestContext context) {
    marcXmlParserFromFile()
        .compose(parser -> {
          parser.fetch(1);
          parser.pause();
          parser.fetch(1);
          Promise<Void> promise = Promise.promise();
          AtomicInteger cnt = new AtomicInteger();
          parser.exceptionHandler(promise::tryFail);
          parser.endHandler(promise::tryComplete);
          parser.handler(x -> {
            if (cnt.incrementAndGet() == 1) {
              parser.pause();
              vertx.setTimer(10, p -> parser.resume());
            }
          });
          return promise.future();
        })
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testDoubleEnd(TestContext context) {
    marcXmlParserFromFile()
        .map(parser -> {
          parser.end();
          parser.endHandler(x -> {});
          parser.end();
          return null;
        })
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), is("Parsing already done"))));
  }

  @Test
  public void exceptionInHandler(TestContext context) {
    marcXmlParserFromFile().compose(parser -> {
          Promise<Void> promise = Promise.promise();
          parser.handler(r -> {
            throw new RuntimeException("handler exception");
          });
          parser.exceptionHandler(promise::tryFail);
          parser.endHandler(promise::complete);
          parser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), is("handler exception"))));
  }

  @Test
  public void testNoCollectionElement(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(Buffer.buffer("<x/>"), vertx);
    MarcXmlParserToJson parser = new MarcXmlParserToJson(XmlParser.newParser(rs));
    Promise<Void> promise = Promise.promise();
    parser.exceptionHandler(promise::tryFail);
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), is("Expected <collection> as root tag. Got x"))));
  }

  @Test
  public void testNoRecordElement(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(Buffer.buffer("<collec"), null, null, Buffer.buffer("tion><y/></collection>"), 0, vertx);
    MarcXmlParserToJson parser = new MarcXmlParserToJson(XmlParser.newParser(rs));
    Promise<Void> promise = Promise.promise();
    parser.exceptionHandler(promise::tryFail);
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), is("Expected <record> as 2nd-level. Got y"))));
  }

  @Test
  public void testNoRecordElement2(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(Buffer.buffer("<collection>ignored<y/></collection>"), vertx);
    MarcXmlParserToJson parser = new MarcXmlParserToJson(XmlParser.newParser(rs));
    Promise<Void> promise = Promise.promise();
    parser.exceptionHandler(promise::tryFail);
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), is("Expected <record> as 2nd-level. Got y"))));
  }


  @Test
  public void testNoRecordElementNoExceptionHandler(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(Buffer.buffer("<collec"), null, null, Buffer.buffer("tion><y/></collection>"), 0, vertx);
    MarcXmlParserToJson parser = new MarcXmlParserToJson(XmlParser.newParser(rs));
    Promise<Void> promise = Promise.promise();
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testEmptyCollection(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(Buffer.buffer("<collection>ignored</collection>"), vertx);
    MarcXmlParserToJson parser = new MarcXmlParserToJson(XmlParser.newParser(rs));
    Promise<Void> promise = Promise.promise();
    parser.exceptionHandler(promise::tryFail);
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future().onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void testExceptionInStream(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(null, vertx);
    MarcXmlParserToJson parser = new MarcXmlParserToJson(XmlParser.newParser(rs));
    Promise<Void> promise = Promise.promise();
    parser.exceptionHandler(promise::tryFail);
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), is("Cannot invoke \"io.vertx.core.buffer.Buffer.getBytes()\" because \"buffer\" is null"))));
  }


}
