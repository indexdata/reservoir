package org.folio.reservoir.util.readstream;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.OpenOptions;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.xml.stream.XMLStreamConstants;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

@RunWith(VertxUnitRunner.class)

public class XmlFixerTest {

  Vertx vertx;
  @Before
  public void before() {
    vertx = Vertx.vertx();
  }

  @After
  public void after(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  Future<Buffer> bufferFromFile(String fname) {
    return vertx.fileSystem().open(fname, new OpenOptions())
        .compose(asyncFile -> {
          XmlFixer xmlFixer = new XmlFixer(asyncFile);
          Buffer buffer = Buffer.buffer();
          Promise<Buffer> promise = Promise.promise();
          xmlFixer.handler(buffer::appendBuffer);
          xmlFixer.exceptionHandler(e -> promise.tryFail(e));
          xmlFixer.endHandler(x -> promise.tryComplete(buffer));
          return promise.future();
        });
  }

  Future<XmlParser> xmlParserFromFile(String fname) {
    return vertx.fileSystem().open(fname, new OpenOptions())
        .map(asyncFile -> {
          XmlParser xmlParser = XmlParser.newParser(new XmlFixer(asyncFile));
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
  public void small(TestContext context) {
    eventsFromFile("small.xml").onComplete(context.asyncAssertSuccess(events -> {
      assertThat(events, contains(XMLStreamConstants.START_DOCUMENT,
          XMLStreamConstants.START_ELEMENT, XMLStreamConstants.END_ELEMENT,
          XMLStreamConstants.END_DOCUMENT));
    }));
  }

  @Test
  public void smallBadEntities(TestContext context) {
    eventsFromFile("small-bad-entities.xml").onComplete(context.asyncAssertSuccess(events -> {
      assertThat(events, contains(XMLStreamConstants.START_DOCUMENT,
          XMLStreamConstants.START_ELEMENT, XMLStreamConstants.CHARACTERS,
          XMLStreamConstants.END_ELEMENT,
          XMLStreamConstants.END_DOCUMENT));
    }));
  }

  @Test
  public void badMarcXml(TestContext context) {
    bufferFromFile("bad-marcxml.xml").onComplete(context.asyncAssertSuccess(buffer -> {
      assertThat(buffer.toString(), containsString("<subfield code=\"å\">00E5</subfield>"));
      assertThat(buffer.toString(), containsString("<subfield code=\"丐\">4E10</subfield>"));
      assertThat(buffer.toString(), containsString("<subfield code=\"\uD83C\uDCA1\">1F0A1</subfield>"));
      assertThat(buffer.toString(), containsString("<subfield code=\"ø\">&#xFFFD;F8 never allowed</subfield>"));
      assertThat(buffer.toString(), containsString("<subfield code=\"o\">&#xFFFD;B8 out of sequence</subfield>"));
    }));
  }

  @Test
  public void badMarcXml2(TestContext context) {
    eventsFromFile("bad-marcxml.xml").onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void badOaiResponse1(TestContext context) {
    bufferFromFile("pennstate-bad-rec-20221216.xml").onComplete(context.asyncAssertSuccess(buffer -> {
      assertThat(buffer.toString(), containsString("3659107")); // resumption token which comes last
    }));
  }

  @Test
  public void badOaiResponse2(TestContext context) {
    eventsFromFile("pennstate-bad-rec-20221216.xml").onComplete(context.asyncAssertSuccess(events -> {
      assertThat(events, hasSize(105993));
    }));
  }

}
