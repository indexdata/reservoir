package org.folio.reservoir.util;

import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * MARCXML to MARC-in-JSON conversion.
 */
public final class MarcXmlToJson {

  private MarcXmlToJson() { }

  /**
   * Convert MARCXML to MARC-in-JSON from String.
   * @param marcXml MARCXML XML string
   * @return JSON object.
   * @throws XMLStreamException some stream exception
   */
  public static JsonObject convert(String marcXml) throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    XMLStreamReader xmlStreamReader =
        factory.createXMLStreamReader(new ByteArrayInputStream(marcXml.getBytes()));
    return convert(xmlStreamReader);
  }

  /**
   * Convert one MARCXML instance to MARC-in-JSON from XMLStreamReader.
   * @param xmlStreamReader were the MARC-XML is read from
   * @return JSON object.
   */
  public static JsonObject convert(XMLStreamReader xmlStreamReader) {
    try {
      XmlMetadataParserMarcInJson parserMarcInJson = new XmlMetadataParserMarcInJson();
      int level = 0;
      while (xmlStreamReader.hasNext()) {
        int e = xmlStreamReader.next();
        if (XMLStreamConstants.START_ELEMENT == e) {
          level++;
        } else if (XMLStreamConstants.END_ELEMENT == e) {
          level--;
          if (level == 0) {
            break;
          }
        }
        parserMarcInJson.handle(xmlStreamReader);
      }
      return parserMarcInJson.result();
    } catch (XMLStreamException e) {
      throw new IllegalArgumentException(e.getMessage());
    }
  }
}
