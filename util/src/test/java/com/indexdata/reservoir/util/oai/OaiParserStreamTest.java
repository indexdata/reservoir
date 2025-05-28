package org.folio.reservoir.util.oai;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.folio.reservoir.util.XmlMetadataParserMarcInJson;
import org.folio.reservoir.util.XmlMetadataStreamParser;
import org.folio.reservoir.util.readstream.XmlParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@RunWith(VertxUnitRunner.class)
public class OaiParserStreamTest {

  Vertx vertx;
  @Before
  public void before() {
    vertx = Vertx.vertx();
  }

  @After
  public void after(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  Future<OaiParserStream<JsonObject>> parseOai(String fname, Consumer<OaiRecord<JsonObject>> recordHandler) {
    return vertx.fileSystem().open(fname, new OpenOptions()).compose(asyncFile -> {
      XmlParser xmlParser = XmlParser.newParser(asyncFile);
      XmlMetadataStreamParser<JsonObject> metadataParser = new XmlMetadataParserMarcInJson();
      OaiParserStream<JsonObject> oaiParserStream = new OaiParserStream<>(xmlParser, metadataParser);
      oaiParserStream.parse(recordHandler);
      Promise<OaiParserStream<JsonObject>> promise = Promise.promise();
      oaiParserStream.exceptionHandler(e -> promise.tryFail(e));
      xmlParser.endHandler(e -> promise.complete(oaiParserStream));
      return promise.future();
    });
  }

 @Test
 public void listRecords1(TestContext context) {
    List<OaiRecord<JsonObject>> records = new ArrayList<>();
    parseOai("oai-response-1.xml", records::add)
        .onComplete(context.asyncAssertSuccess(oaiParserStream -> {
          assertThat(records, hasSize(4));
          assertThat(records.get(0).deleted, is(true));
          assertThat(records.get(1).deleted, is(false));
          assertThat(records.get(2).deleted, is(false));
          assertThat(records.get(3).deleted, is(false));
          assertThat(records.get(0).identifier, is("998212783503681"));
          assertThat(records.get(1).identifier, is("9977919382003681"));
          assertThat(records.get(2).identifier, is("9977924842403681"));
          assertThat(records.get(3).identifier, is("9977648149503681"));
          assertThat(records.get(0).metadata, nullValue());
          assertThat(records.get(1).metadata.getString("leader"), is("10873cam a22004693i 4500"));
          assertThat(records.get(2).metadata.getString("leader"), is("02052cam a22004213i 4500"));
          assertThat(records.get(3).metadata.getString("leader"), is("02225nam a2200469 i 4500"));
          assertThat(oaiParserStream.getResumptionToken(), is("MzM5OzE7Ozt2MS4w"));
          assertThat(records.get(3).datestamp, is("2022-05-03"));
        }));
  }

  @Test
  public void listRecords2(TestContext context) {
    List<OaiRecord<JsonObject>> records = new ArrayList<>();
    parseOai("oai-response-2.xml", records::add)
        .onComplete(context.asyncAssertSuccess(oaiParserStream -> {
          assertThat(records, empty());
          assertThat(oaiParserStream.getResumptionToken(), nullValue());
          assertThat(oaiParserStream.getError(), is("noRecordsMatch: The combination of the values of the from, until, set and metadataPrefix arguments returns an empty list"));
        }));
  }

  @Test
  public void listRecords3(TestContext context) {
    List<OaiRecord<JsonObject>> records = new ArrayList<>();
    parseOai("oai-response-3.xml", records::add)
        .onComplete(context.asyncAssertSuccess(oaiParserStream -> {
          assertThat(oaiParserStream.getResumptionToken(), is("MzM5OzE7Ozt2MS4w"));
          assertThat(records, hasSize(1));
          assertThat(records.get(0).getMetadata(), nullValue());
          assertThat(records.get(0).isDeleted(), is(true));
          assertThat(records.get(0).getDatestamp(), is("2022-05-03"));
          assertThat(records.get(0).getIdentifier(), is("998212783503681"));
        }));
  }

  @Test
  public void listRecords4(TestContext context) {
    List<OaiRecord<JsonObject>> records = new ArrayList<>();
    parseOai("oai-response-4.xml", records::add)
        .onComplete(context.asyncAssertSuccess(oaiParserStream -> {
          assertThat(records, hasSize(4));
          assertThat(records.get(0).deleted, is(true));
          assertThat(records.get(1).deleted, is(false));
          assertThat(records.get(2).deleted, is(false));
          assertThat(records.get(3).deleted, is(false));
          assertThat(records.get(0).identifier, is("998212783503681"));
          assertThat(records.get(1).identifier, is("9977919382003681"));
          assertThat(records.get(2).identifier, is("9977924842403681"));
          assertThat(records.get(3).identifier, is("9977648149503681"));
          assertThat(records.get(0).metadata, nullValue());
          assertThat(records.get(1).metadata, nullValue());
          assertThat(records.get(2).metadata, nullValue());
          assertThat(records.get(3).metadata, nullValue());
          assertThat(oaiParserStream.getResumptionToken(), is("MzM5OzE7Ozt2MS4w"));
          assertThat(records.get(3).datestamp, is("2022-05-03"));
        }));
  }

  @Test
  public void listRecords5(TestContext context) {
    List<OaiRecord<JsonObject>> records = new ArrayList<>();
    parseOai("oai-response-5.xml", records::add)
        .onComplete(context.asyncAssertFailure(e -> {
          assertThat(e.getMessage(), is("Bad marcxml element: foo"));
          assertThat(records, hasSize(1));
          assertThat(records.get(0).identifier, is("998212783503681"));
        }));
  }
}
