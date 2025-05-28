package org.folio.reservoir.util.readstream;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.marc4j.marc.Record;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@RunWith(VertxUnitRunner.class)
public class MarcToJsonParserTest {
  Vertx vertx;
  @Before
  public void before() {
    vertx = Vertx.vertx();
  }

  @After
  public void after(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  Future<ReadStream<JsonObject>> marc4ParserToXmlFromFile(String fname) {
    return vertx.fileSystem().open(fname, new OpenOptions())
        .map(f -> new MarcToJsonParser(f).pause());
  }

  Future<ReadStream<JsonObject>> marc4ParserToXmlFromFile() {
    return marc4ParserToXmlFromFile("marc3.marc");
  }

  @Test
  public void marc3(TestContext context) {
    marc4ParserToXmlFromFile()
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
          //rec1
          assertThat(records.get(0).getJsonArray("fields").getJsonObject(0).getString("001"), is("   73209622 //r823"));
          assertThat(records.get(0).getJsonArray("fields").getJsonObject(9).getJsonObject("245").getJsonArray("subfields").getJsonObject(0).getString("a"), is("The Computer Bible /"));
          assertThat(records.get(0).getJsonArray("fields").getJsonObject(9).getJsonObject("245").getString("ind1"), is("0"));
          assertThat(records.get(0).getJsonArray("fields").getJsonObject(9).getJsonObject("245").getString("ind2"), is("4"));
          //rec2
          assertThat(records.get(1).getJsonArray("fields").getJsonObject(0).getString("001"), is("   11224466 "));
          //the following should be at pos 8 if we follow yaz order, marc4j puts 010 as last field following marc physicall order
          assertThat(records.get(1).getJsonArray("fields").getJsonObject(7).getJsonObject("245").getJsonArray("subfields").getJsonObject(0).getString("a"), is("How to program a computer"));
          assertThat(records.get(1).getJsonArray("fields").getJsonObject(7).getJsonObject("245").getString("ind1"), is("1"));
          assertThat(records.get(1).getJsonArray("fields").getJsonObject(7).getJsonObject("245").getString("ind2"), is("0"));
          //rec3
          assertThat(records.get(2).getJsonArray("fields").getJsonObject(0).getString("001"), is("   77123332 "));
          assertThat(records.get(2).getJsonArray("fields").getJsonObject(10).getJsonObject("245").getJsonArray("subfields").getJsonObject(0).getString("a"), is("Voyager Diacritic test -- New input 001 (SBIE)."));
          assertThat(records.get(2).getJsonArray("fields").getJsonObject(10).getJsonObject("245").getString("ind1"), is("0"));
          assertThat(records.get(2).getJsonArray("fields").getJsonObject(10).getJsonObject("245").getString("ind2"), is("0"));
        }));
  }

  @Test
  public void testEndHandler(TestContext context) {
    marc4ParserToXmlFromFile()
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
    marc4ParserToXmlFromFile()
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
  public void testEndHandlerExceptionNoExceptionHandler(TestContext context) {
    marc4ParserToXmlFromFile()
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
    marc4ParserToXmlFromFile()
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
    marc4ParserToXmlFromFile()
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
  public void exceptionInHandler(TestContext context) {
    marc4ParserToXmlFromFile().compose(parser -> {
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
  public void testSkipLead(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(Buffer.buffer("!" + "x".repeat(24)), vertx);
    MappingReadStream<Record, Buffer> parser = new MappingReadStream<>(rs, new Marc4jMapper());
    Promise<Void> promise = Promise.promise();
    parser.exceptionHandler(promise::tryFail);
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertFailure());
  }

  @Test
  public void testAllLeadBad(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(Buffer.buffer("!".repeat(4) + "9".repeat(23)), vertx);
    MappingReadStream<Record, Buffer> parser = new MappingReadStream<>(rs, new Marc4jMapper());
    Promise<Void> promise = Promise.promise();
    parser.exceptionHandler(promise::tryFail);
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertFailure());
  }

  @Test
  public void testExceptionInStream(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(null, vertx);
    MappingReadStream<Record, Buffer> parser = new MappingReadStream<>(rs, new Marc4jMapper());
    Promise<Void> promise = Promise.promise();
    parser.exceptionHandler(promise::tryFail);
    parser.endHandler(x -> promise.complete());
    parser.resume();
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), is("Cannot read field \"buffer\" because \"impl\" is null"))));
  }

  @Test
  public void testExceptionInStreamNoExceptionHandler(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(null, vertx);
    MappingReadStream<Record, Buffer> parser = new MappingReadStream<>(rs, new Marc4jMapper());
    Promise<Void> promise = Promise.promise();
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertSuccess());
  }
}
