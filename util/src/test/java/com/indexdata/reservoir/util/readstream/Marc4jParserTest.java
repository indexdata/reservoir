package org.folio.reservoir.util.readstream;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.DecodeException;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.marc4j.marc.Record;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@RunWith(VertxUnitRunner.class)
public class Marc4jParserTest {
  Vertx vertx;
  @Before
  public void before() {
    vertx = Vertx.vertx();
  }

  @After
  public void after(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  Future<MappingReadStream<Record, Buffer>> marc4jParserFromFile(String fname) {
    return vertx.fileSystem().open(fname, new OpenOptions())
        .map(file -> {
          var parser = new MappingReadStream<>(file, new Marc4jMapper());
          parser.pause();
          return parser;
        });
  }

  Future<MappingReadStream<Record, Buffer>> marc4jParserFromFile() {
    return marc4jParserFromFile("marc3.marc");
  }

  @Test
  public void marc3(TestContext context) {
    marc4jParserFromFile()
        .compose(parser -> {
          List<Record> records = new ArrayList<>();
          Promise<List<Record>> promise = Promise.promise();
          parser.handler(records::add);
          parser.endHandler(e -> promise.complete(records));
          parser.exceptionHandler(promise::tryFail);
          parser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertSuccess(records -> {
          assertThat(records, hasSize(3));
          assertThat(records.get(0).getControlNumber(), is("   73209622 //r823"));
          assertThat(records.get(1).getControlNumber(), is("   11224466 "));
          assertThat(records.get(2).getControlNumber(), is("   77123332 "));
        }));
  }

  @Test
  public void testEndHandler(TestContext context) {
    marc4jParserFromFile()
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
    marc4jParserFromFile()
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
    marc4jParserFromFile()
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
    marc4jParserFromFile()
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
    marc4jParserFromFile()
        .compose(parser -> {
          parser.resume();
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
    marc4jParserFromFile()
        .map(parser -> {
          parser.resume();
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
    marc4jParserFromFile().compose(parser -> {
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
  public void testBadMarc(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(Buffer.buffer("x00025" + "9".repeat(20)), vertx);
    MappingReadStream<Record, Buffer> parser = new MappingReadStream<>(rs, new Marc4jMapper());
    Promise<Void> promise = Promise.promise();
    parser.exceptionHandler(promise::tryFail);
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), is("unable to parse record length"))));

  }

  @Test
  public void testBadMarc2(TestContext context) {
    MemoryReadStream rs = new MemoryReadStream(Buffer.buffer("x00024"), Buffer.buffer("9"), 19, vertx);
    MappingReadStream<Record, Buffer> parser = new MappingReadStream<>(rs, new Marc4jMapper());
    Promise<Void> promise = Promise.promise();
    parser.exceptionHandler(promise::tryFail);
    parser.endHandler(x -> promise.complete());
    rs.run();
    promise.future()
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), is("unable to parse record length"))));
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

  static int getNext(String b, boolean ended) {
    return Marc4jMapper.parseMarcBuffer(Buffer.buffer(b), 0, ended);
  }

  @Test
  public void getNextTest() {
    assertThat(getNext("0000", false), is(0));
    assertThat(getNext("x0000", false), is(0));
    assertThat(getNext("xxx0000", false), is(0));
    assertThat(getNext("000", true), is(3));
    Assert.assertThrows(DecodeException.class, () -> getNext("xxxxx00000", false));
    Assert.assertThrows(DecodeException.class, () -> getNext("x00023", false));
    assertThat(getNext("x00024" + "0".repeat(18), false), is(0));
    assertThat(getNext("x00024" + "0".repeat(18), false), is(0));
    assertThat(getNext("x00024" + "0".repeat(18), true), is(23));
    assertThat(getNext("x00024" + "0".repeat(19), false), is(24));
  }

}
