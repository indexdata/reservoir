package org.folio.reservoir.util;

import static org.folio.reservoir.util.MarcConstants.COLLECTION_LABEL;
import static org.folio.reservoir.util.MarcConstants.RECORD_LABEL;

import io.vertx.core.json.JsonObject;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

public final class IngestRecord {

  private IngestRecord() {}

  static JsonObject createIngestRecord(JsonObject marcPayload, JsonObject stylesheetResult) {
    if (stylesheetResult.containsKey(COLLECTION_LABEL)) {
      stylesheetResult = stylesheetResult.getJsonObject(COLLECTION_LABEL);
    }
    JsonObject inventoryPayload = stylesheetResult.getJsonObject(RECORD_LABEL);
    if (inventoryPayload == null) {
      throw new IllegalArgumentException("inventory xml: missing record property");
    }
    String localId = inventoryPayload.getString("localIdentifier");
    if (localId == null) {
      throw new IllegalArgumentException("inventory xml: missing record/localIdentifier string");
    }
    inventoryPayload.remove("original");
    return new JsonObject()
        .put("localId", localId)
        .put("payload", new JsonObject()
            .put("marc", marcPayload)
            .put("inventory", inventoryPayload));
  }


  /**
   * Create ingest object with "localId", "marcPayload", "inventoryPayload".
   *
   * @param marcXml   MARC XML string
   * @param templates List of XSLT templates to apply
   * @return ingest JSON object
   * @throws TransformerException transformer problem
   * @throws XMLStreamException   xml stream problem (Invalid XML)
   */
  public static JsonObject createIngestRecord(String marcXml, List<Templates> templates)
      throws TransformerException, XMLStreamException {

    String inventory = marcXml;
    for (Templates template : templates) {
      Source source = new StreamSource(new StringReader(inventory));
      StreamResult result = new StreamResult(new StringWriter());
      Transformer transformer = template.newTransformer();
      transformer.transform(source, result);
      inventory = result.getWriter().toString();
    }
    return createIngestRecord(MarcXmlToJson.convert(marcXml),
        InventoryToXml.inventoryXmlToJson(inventory));
  }
}
