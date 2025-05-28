package org.folio.reservoir.util.readstream;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.DecodeException;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@RunWith(VertxUnitRunner.class)
public class XmlParserTest {
  Vertx vertx;
  @Before
  public void before() {
    vertx = Vertx.vertx();
  }

  @After
  public void after(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  Future<XmlParser> xmlParserFromFile(String fname) {
    return vertx.fileSystem().open(fname, new OpenOptions())
        .map(asyncFile -> {
          XmlParser xmlParser = XmlParser.newParser(asyncFile);
          xmlParser.pause();
          return xmlParser;
        });
  }

  Future<List<Integer>> eventsFromFile(String fname) {
    List<Integer> events = new ArrayList<>();
    return xmlParserFromFile(fname).compose(xmlParser -> {
          Promise<List<Integer>> promise = Promise.promise();
          xmlParser.handler(event -> events.add(event.getEventType()));
          xmlParser.endHandler(e -> promise.complete(events));
          xmlParser.exceptionHandler(e -> promise.tryFail(e));
          xmlParser.resume();
          return promise.future();
        });
  }

  @Test
  public void record10(TestContext context) {
    eventsFromFile("record10.xml").onComplete(context.asyncAssertSuccess(events -> {
      assertThat(events, hasSize(2965));
    }));
  }

  @Test
  public void feedInputThrowsException() {
    XmlMapper xmlMapper = new XmlMapper();
    xmlMapper.push(Buffer.buffer("<"));
    xmlMapper.end();
    Buffer xml2 = Buffer.buffer("xml/>");
    // we are violating the contract by using push after end
    XmlMapperException e = Assert.assertThrows(XmlMapperException.class, () -> xmlMapper.push(xml2));
    assertThat(e.getMessage(), is("Still have 1 unread bytes"));
  }

  @Test
  public void small(TestContext context) {
    eventsFromFile("small.xml").onComplete(context.asyncAssertSuccess(events -> {
      assertThat(events, contains(XMLStreamConstants.START_DOCUMENT,
          XMLStreamConstants.START_ELEMENT, XMLStreamConstants.END_ELEMENT,
          XMLStreamConstants.END_DOCUMENT));
    }));
  }

  @Test
  public void bad(TestContext context) {
    eventsFromFile("bad.xml").onComplete(context.asyncAssertFailure(e -> {
      assertThat(e.getMessage(), containsString("Unexpected character '<'"));
    }));
  }

  @Test
  public void testEnd(TestContext context) {
    List<Integer> events = new LinkedList<>();
    xmlParserFromFile("small.xml")
        .compose(xmlParser -> {
          Promise<Void> promise = Promise.promise();
          xmlParser.exceptionHandler(promise::tryFail);
          xmlParser.endHandler(end -> promise.tryComplete());
          xmlParser.handler(event -> events.add(event.getEventType()));
          xmlParser.pause();
          xmlParser.fetch(2);
          vertx.setTimer(50, x -> {
            assertThat(events, hasSize(2));
            xmlParser.end();
            Assert.assertThrows(IllegalStateException.class, () -> xmlParser.end());
          });
          return promise.future();
        })
        .onComplete(context.asyncAssertSuccess(
            end -> assertThat(events, hasSize(2))));
  }

  @Test
  public void pauseResume(TestContext context) {
    List<Integer> events = new LinkedList<>();
    xmlParserFromFile("small.xml")
        .compose(xmlParser -> {
           Promise<Void> promise = Promise.promise();
           xmlParser.exceptionHandler(promise::tryFail);
           xmlParser.endHandler(end -> promise.tryComplete());
           xmlParser.handler(event -> events.add(event.getEventType()));
           xmlParser.pause();
           xmlParser.fetch(2);
           vertx.setTimer(100, x -> {
             assertThat(events, hasSize(2));
             xmlParser.resume();
             xmlParser.resume(); // provoke overflow in ReadStream.fetch
           });
           return promise.future();
         })
         .onComplete(context.asyncAssertSuccess(
             end -> assertThat(events, hasSize(4))));
  }

  @Test
  public void emittingInEffect(TestContext context) {
    List<Integer> events = new LinkedList<>();
    xmlParserFromFile("small.xml")
        .compose(xmlParser -> {
          Promise<Void> promise = Promise.promise();
          xmlParser.exceptionHandler(promise::tryFail);
          xmlParser.endHandler(end -> promise.tryComplete());
          xmlParser.handler(event -> {
            events.add(event.getEventType());
            xmlParser.pause();
            xmlParser.fetch(1);
          });
          xmlParser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertSuccess(
            end -> assertThat(events, hasSize(4))));
  }

  @Test
  public void noEndHandler(TestContext context) {
    List<Integer> events = new LinkedList<>();
    xmlParserFromFile("small.xml")
        .compose(xmlParser -> {
          Promise<Void> promise = Promise.promise();
          xmlParser.handler(event -> {
            events.add(event.getEventType());
            if (events.size() == 4) {
              promise.tryComplete();
            }
          });
          xmlParser.exceptionHandler(promise::tryFail);
          xmlParser.pause();
          vertx.setTimer(5, x1 -> {
            assertThat(events, empty());
            xmlParser.resume();
          });
          return promise.future();
        })
        .onComplete(context.asyncAssertSuccess(
            end -> assertThat(events, hasSize(4))));
  }

  @Test
  public void exceptionInHandler(TestContext context) {
    List<Integer> events = new LinkedList<>();
    xmlParserFromFile("small.xml")
        .compose(xmlParser -> {
          Promise<Void> promise = Promise.promise();
          xmlParser.handler(event -> {
            events.add(event.getEventType());
            throw new RuntimeException("handlerExcept");
          });
          xmlParser.exceptionHandler(promise::tryFail);
          xmlParser.endHandler(promise::tryComplete);
          xmlParser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), is("handlerExcept"))));
  }


  @Test
  public void xmlStreamExceptionWithHandler(TestContext context) {
    List<Integer> events = new LinkedList<>();
    xmlParserFromFile("bad.xml")
        .compose(xmlParser -> {
          Promise<Void> promise = Promise.promise();
          xmlParser.handler(event -> events.add(event.getEventType()));
          xmlParser.exceptionHandler(promise::tryFail);
          xmlParser.endHandler(promise::tryComplete);
          xmlParser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertFailure(
            e -> assertThat(e.getMessage(), containsString("Unexpected character '<'"))));
  }

  @Test
  public void xmlStreamExceptionNoHandler(TestContext context) {
    List<Integer> events = new LinkedList<>();
    xmlParserFromFile("bad.xml")
        .compose(xmlParser -> {
          Promise<Void> promise = Promise.promise();
          xmlParser.handler(event -> events.add(event.getEventType()));
          xmlParser.endHandler(promise::complete);
          xmlParser.resume();
          return promise.future();
        })
        .onComplete(context.asyncAssertSuccess(
            end -> assertThat(events, hasSize(2))));
  }

  @Test
  public void testIncomplete() {
    XmlMapper xmlMapper = new XmlMapper();
    xmlMapper.push(Buffer.buffer("<foo"));
    XMLStreamReader poll = xmlMapper.poll();
    assertThat(poll.getEventType(), is(XMLStreamConstants.START_DOCUMENT));
    poll = xmlMapper.poll();
    assertThat(poll, nullValue());
    xmlMapper.end();
    XmlMapperException e = Assert.assertThrows(XmlMapperException.class, () -> xmlMapper.poll());
    assertThat(e.getMessage(), containsString("Incomplete input"));
  }

  @Test
  public void testBad1() {
    XmlMapper xmlMapper = new XmlMapper();
    xmlMapper.push(Buffer.buffer("Hello"));
    XMLStreamReader poll = xmlMapper.poll();
    assertThat(poll.getEventType(), is(XMLStreamConstants.START_DOCUMENT));
    XmlMapperException e = Assert.assertThrows(XmlMapperException.class, () -> xmlMapper.poll());
    assertThat(e.getMessage(), containsString("Unexpected character 'H'"));
  }

  @Test
  public void testBad() {
    XmlMapper xmlMapper = new XmlMapper();
    xmlMapper.push(Buffer.buffer("<foo><</end>"));
    XMLStreamReader poll = xmlMapper.poll();
    assertThat(poll.getEventType(), is(XMLStreamConstants.START_DOCUMENT));
    poll = xmlMapper.poll();
    assertThat(poll.getEventType(), is(XMLStreamConstants.START_ELEMENT));
    XmlMapperException e = Assert.assertThrows(XmlMapperException.class, () -> xmlMapper.poll());
    assertThat(e.getMessage(), containsString("Unexpected character '<'"));
  }
}
