package org.folio.reservoir.util;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Iterator;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Inventory XML to JSON conversion.
 *
 * <p>This conversion is used when XSLT is used
 * to parse inventory information out of MARC-XML records. This in turn must be
 * converted to JSON in a special way - with for example special property "i" to
 * make conversion to JSON array occur.
 */
public final class InventoryToXml {

  private static final Logger LOGGER = LogManager.getLogger(InventoryToXml.class);

  private InventoryToXml() { }

  static String getXmlStreamerEventInfo(int event, XMLStreamReader xmlStreamReader) {
    switch (event) {
      case XMLStreamConstants.END_ELEMENT:
        return "END " + xmlStreamReader.getLocalName();
      case XMLStreamConstants.START_ELEMENT:
        return "START " + xmlStreamReader.getLocalName();
      case XMLStreamConstants.CHARACTERS:
        return "CHARACTERS '" + xmlStreamReader.getText() + "'";
      default:
        return String.valueOf(event);
    }
  }

  private static int next(XMLStreamReader xmlStreamReader) throws XMLStreamException {
    int event = xmlStreamReader.next();
    LOGGER.debug("next {}", () -> getXmlStreamerEventInfo(event, xmlStreamReader));
    return event;
  }

  static JsonArray xmlToJsonArray(int depth, XMLStreamReader xmlStreamReader, String skip)
      throws XMLStreamException {
    JsonArray ar = new JsonArray();
    while (true) {
      int event = next(xmlStreamReader);
      if (event == XMLStreamConstants.START_ELEMENT) {
        JsonObject arrayObject = new JsonObject();
        xmlToJsonObject(depth + 1, xmlStreamReader, skip, event, arrayObject);
        Iterator<String> iterator = arrayObject.fieldNames().iterator();
        // take content of "i" element
        if (iterator.hasNext()) {
          ar.add(arrayObject.getValue(iterator.next()));
        }
      } else if (event != XMLStreamConstants.CHARACTERS) {
        break;
      }
    }
    return ar;
  }

  static void xmlToJsonSkip(XMLStreamReader xmlStreamReader, int event) throws XMLStreamException {
    int level = 0;
    while (true) {
      if (event == XMLStreamConstants.END_ELEMENT) {
        level--;
        if (level == 0) {
          break;
        }
      } else if (event == XMLStreamConstants.START_ELEMENT) {
        level++;
      }
      event = next(xmlStreamReader);
    }
  }

  static Object xmlToJsonObject(int depth, XMLStreamReader xmlStreamReader, String skip, int event,
      JsonObject arrayObject) throws XMLStreamException {
    StringBuilder text = null;
    JsonObject o = arrayObject;
    JsonArray ar = null;
    while (true) {
      if (event == XMLStreamConstants.START_ELEMENT) {
        String localName = xmlStreamReader.getLocalName();
        if (arrayObject == null && "arr".equals(localName)) {
          ar = xmlToJsonArray(depth, xmlStreamReader, skip);
        } else if (skip.equals(localName)) {
          xmlToJsonSkip(xmlStreamReader, event);
        } else {
          event = next(xmlStreamReader);
          if (o == null) {
            o = new JsonObject();
          }
          o.put(localName, xmlToJsonObject(depth + 1, xmlStreamReader, skip, event, null));
          if (!xmlStreamReader.hasNext() || arrayObject != null) {
            return o;
          }
        }
      } else if (arrayObject == null && event == XMLStreamConstants.CHARACTERS) {
        if (text == null) {
          text = new StringBuilder();
        }
        text.append(xmlStreamReader.getText());
      } else {
        break;
      }
      event = next(xmlStreamReader);
    }
    if (ar != null) {
      return ar;
    } else if (o != null) {
      return o;
    } else if (text != null) {
      return text.toString();
    } else {
      return null;
    }
  }

  /**
   * Convert "inventory" XML to JSON.
   * @param xml inventory XML
   * @return json object without original record
   * @throws XMLStreamException bad XML
   */
  public static JsonObject inventoryXmlToJson(String xml) throws XMLStreamException {
    InputStream stream = new ByteArrayInputStream(xml.getBytes());
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);
    Object o = xmlToJsonObject(0, xmlStreamReader, "original", next(xmlStreamReader), null);
    if (o instanceof JsonObject jsonObject) {
      return jsonObject;
    }
    throw new IllegalArgumentException("xmlToJsonObject not returning JsonObject");
  }

}
