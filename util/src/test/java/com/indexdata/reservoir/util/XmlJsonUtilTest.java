package com.indexdata.reservoir.util;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.ByteArrayInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.Assert;
import org.junit.Test;

public class XmlJsonUtilTest {
  static final String MARCXML1_SAMPLE =
      "<record xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
          + "  <leader>1234&lt;&gt;&quot;&apos;</leader>\n"
          + "</record>";

  static final JsonObject MARCJSON1_SAMPLE = new JsonObject().put("leader", "1234<>\"'");

  static final String MARCXML2_SAMPLE =
      "<record xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
          + "  <leader>01010ccm a2200289   4500</leader>\n"
          + "  <controlfield tag=\"001\">a1</controlfield>\n"
          + "  <datafield tag=\"010\" ind1=\" \" ind2=\"&amp;\">\n"
          + "    <subfield code=\"a\">   70207870&lt;</subfield>\n"
          + "  </datafield>\n"
          + "  <datafield tag=\"245\">\n"
          + "    <subfield code=\"a\">Titlea</subfield>\n"
          + "    <subfield code=\"b\">Title&amp;b</subfield>\n"
          + "  </datafield>\n"
          + "</record>";

  static final JsonObject MARCJSON2_SAMPLE = new JsonObject()
      .put("leader", "01010ccm a2200289   4500")
      .put("fields", new JsonArray()
          .add(new JsonObject().put("001", "a1"))
          .add(new JsonObject().put("010", new JsonObject()
                  .put("ind1", " ")
                  .put("ind2", "&")
                  .put("subfields", new JsonArray()
                      .add(new JsonObject()
                          .put("a", "   70207870<"))
                  )
              )
          )
          .add(new JsonObject().put("245", new JsonObject()
                  .put("subfields", new JsonArray()
                      .add(new JsonObject()
                          .put("a", "Titlea"))
                      .add(new JsonObject()
                          .put("b", "Title&b"))
                  )
              )
          )
      );

  static final String MARCXML3_SAMPLE =
      "<record xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
          + "  <controlfield tag=\"001\">a1</controlfield>\n"
          + "</record>";

  static final JsonObject MARCJSON3_SAMPLE = new JsonObject()
      .put("fields", new JsonArray()
          .add(new JsonObject().put("001", "a1"))
      );

  static final String MARCXML_BAD_CHAR_SAMPLE =
      "<record xmlns=\"http://www.loc.gov/MARC21/slim\">\n"
          + "  <controlfield tag=\"001\">&#xFFFD;</controlfield>\n"
          + "</record>";

  static final JsonObject MARCJSON_BAD_CHAR_SAMPLE = new JsonObject()
      .put("fields", new JsonArray()
          .add(new JsonObject().put("001", "\u001d"))
      );

  @Test
  public void convertJsonToMarcXml1() {
    String got = JsonToMarcXml.convert(MARCJSON1_SAMPLE);
    Assert.assertEquals(MARCXML1_SAMPLE, got);
  }

  @Test
  public void convertJsonToMarcXml2() {
    String got = JsonToMarcXml.convert(MARCJSON2_SAMPLE);
    Assert.assertEquals(MARCXML2_SAMPLE, got);
  }

  @Test
  public void convertJsonToMarcXml3() {
    String got = JsonToMarcXml.convert(MARCJSON3_SAMPLE);
    Assert.assertEquals(MARCXML3_SAMPLE, got);
  }

  @Test
  public void convertJsonToMarcXmlBadChar() {
    String got = JsonToMarcXml.convert(MARCJSON_BAD_CHAR_SAMPLE);
    Assert.assertEquals(MARCXML_BAD_CHAR_SAMPLE, got);
  }

  @Test
  public void convertJsonToMarcXmlStream() throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    String doc = "<a>" + MARCXML2_SAMPLE + "</a>";

     XMLStreamReader xmlStreamReader =
        factory.createXMLStreamReader(new ByteArrayInputStream(doc.getBytes()));
     Assert.assertEquals(XMLStreamConstants.START_ELEMENT, xmlStreamReader.next());
     MarcXmlToJson.convert(xmlStreamReader);
     Assert.assertEquals(XMLStreamConstants.END_ELEMENT, xmlStreamReader.next());
     Assert.assertEquals("a", xmlStreamReader.getLocalName());
     Assert.assertEquals(XMLStreamConstants.END_DOCUMENT, xmlStreamReader.next());
     Assert.assertFalse(xmlStreamReader.hasNext());
  }
  @Test
  public void convertMarcXmlToJsonRecord1() throws XMLStreamException {
    JsonObject got = MarcXmlToJson.convert(MARCXML1_SAMPLE);
    Assert.assertEquals(MARCJSON1_SAMPLE, got);
    String collection = "<collection>" + MARCXML1_SAMPLE + "</collection>";
    got = MarcXmlToJson.convert(collection);
    Assert.assertEquals(MARCJSON1_SAMPLE, got);
  }

  @Test
  public void convertMarcXmlToJsonRecord2() throws XMLStreamException {
      JsonObject got = MarcXmlToJson.convert(MARCXML2_SAMPLE);
      Assert.assertEquals(MARCJSON2_SAMPLE, got);

      String collection = "<collection>" + MARCXML2_SAMPLE + "</collection>";
      got = MarcXmlToJson.convert(collection);
      Assert.assertEquals(MARCJSON2_SAMPLE, got);
  }

  @Test
  public void convertMarcXmlToJsonRecord3() throws XMLStreamException {
    JsonObject got = MarcXmlToJson.convert(MARCXML3_SAMPLE);
    Assert.assertEquals(MARCJSON3_SAMPLE, got);

    String collection = "<collection>" + MARCXML3_SAMPLE + "</collection>";
    got = MarcXmlToJson.convert(collection);
    Assert.assertEquals(MARCJSON3_SAMPLE, got);
  }

  @Test
  public void convertMarcXmlToJsonRecordMulti() {
    String collection = "<collection>" + MARCXML1_SAMPLE + MARCXML2_SAMPLE + "</collection>";
    Throwable t = Assert.assertThrows(IllegalArgumentException.class,
        () -> MarcXmlToJson.convert(collection));
    Assert.assertEquals("can not handle multiple records", t.getMessage());
  }

  @Test
  public void convertMarcXmlToJsonRecordMissing()  {
    String record = "<collection/>";
    Throwable t = Assert.assertThrows(IllegalArgumentException.class,
        () -> MarcXmlToJson.convert(record));
    Assert.assertEquals("No record element found", t.getMessage());

    String collection = "<collection><foo/></collection>";
    t = Assert.assertThrows(IllegalArgumentException.class,
        () -> MarcXmlToJson.convert(collection));
    Assert.assertEquals("Bad marcxml element: foo", t.getMessage());
  }

  @Test
  public void removeMarcField1() {
    JsonObject got = MARCJSON1_SAMPLE.copy();
    MarcInJsonUtil.removeMarcField(got, "999");
    JsonObject exp = MARCJSON1_SAMPLE.copy();
    Assert.assertEquals(exp, got);
  }

  @Test
  public void removeMarcField2() {
    JsonObject got = MARCJSON2_SAMPLE.copy();
    MarcInJsonUtil.removeMarcField(got, "300");
    JsonObject exp = MARCJSON2_SAMPLE.copy();
    Assert.assertEquals(exp, got);
  }

  @Test
  public void removeMarcField3() {
    JsonObject got = MARCJSON2_SAMPLE.copy();
    MarcInJsonUtil.removeMarcField(got, "245");
    JsonObject exp = MARCJSON2_SAMPLE.copy();
    exp.getJsonArray("fields").remove(2);
    Assert.assertEquals(exp, got);
  }

  @Test
  public void createMarcDataField1() {
    JsonObject got = MARCJSON1_SAMPLE.copy();
    Assert.assertNull(MarcInJsonUtil.lookupMarcDataField(got, "245", "1", "2"));
    JsonArray ar = MarcInJsonUtil.createMarcDataField(got, "245", "1", "2");
    Assert.assertEquals(ar, MarcInJsonUtil.lookupMarcDataField(got, "245", "1", "2"));
    JsonObject exp = MARCJSON1_SAMPLE.copy();
    exp.put("fields", new JsonArray().add(new JsonObject()
        .put("245", new JsonObject()
            .put("ind1", "1")
            .put("ind2", "2")
            .put("subfields", new JsonArray())
        )));
    Assert.assertEquals(exp, got);
  }

  @Test
  public void createMarcDataField2() {
    JsonObject got = MARCJSON2_SAMPLE.copy();
    JsonArray s200 = MarcInJsonUtil.createMarcDataField(got, "200", "1", "2");
    Assert.assertEquals(s200, MarcInJsonUtil.lookupMarcDataField(got, "200", "1", "2"));
    Assert.assertEquals(s200, MarcInJsonUtil.lookupMarcDataField(got, "200", null, "2"));
    Assert.assertEquals(s200, MarcInJsonUtil.lookupMarcDataField(got, "200", "1", null));
    Assert.assertNull(MarcInJsonUtil.lookupMarcDataField(got, "200", "2", null));
    Assert.assertNull(MarcInJsonUtil.lookupMarcDataField(got, "200", "1", "3"));
    Assert.assertNull(MarcInJsonUtil.lookupMarcDataField(got, "201", "1", "2"));
    MarcInJsonUtil.createMarcDataField(got, "999", " ", " ");
    JsonObject exp = MARCJSON2_SAMPLE.copy();
    exp.getJsonArray("fields")
        .add(2, new JsonObject()
            .put("200", new JsonObject()
                .put("ind1", "1")
                .put("ind2", "2")
                .put("subfields", new JsonArray())
            ))
        .add(new JsonObject()
            .put("999", new JsonObject()
                .put("ind1", " ")
                .put("ind2", " ")
                .put("subfields", new JsonArray())
            ));
    Assert.assertEquals(exp, got);
  }
}
