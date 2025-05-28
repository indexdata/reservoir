package org.folio.reservoir.util;

import static org.folio.reservoir.util.EncodeXmlText.encodeXmlText;

import io.vertx.core.buffer.Buffer;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public final class XmlSerializer {

  private XmlSerializer() { }

  /**
   * Returns XML serialized document for node in XML.
   *
   * <p>This method does not care about namespaces. Only elements (local names), attributes
   * and text is dealt with.
   *
   * @param event event type for node that begins the sub document
   * @param reader XML stream reader
   * @return XML document string; null if no more documents in stream
   * @throws XMLStreamException if there's an exception for the XML stream
   */
  public static String get(int event, XMLStreamReader reader)
      throws XMLStreamException {
    int level = 0;
    Buffer buffer = Buffer.buffer();
    for (; reader.hasNext(); event = reader.next()) {
      switch (event) {
        case XMLStreamConstants.START_ELEMENT:
          level++;
          buffer.appendString("<").appendString(reader.getLocalName());
          if (level == 1) {
            String uri = reader.getNamespaceURI();
            if (uri != null && uri.length() > 0) {
              buffer
                  .appendString(" xmlns=\"")
                  .appendString(uri)
                  .appendString("\"");
            }
          }
          for (int i = 0; i < reader.getAttributeCount(); i++) {
            buffer
                .appendString(" ")
                .appendString(reader.getAttributeLocalName(i))
                .appendString("=\"")
                .appendString(encodeXmlText(reader.getAttributeValue(i)))
                .appendString("\"");
          }
          buffer.appendString(">");
          break;
        case XMLStreamConstants.END_ELEMENT:
          level--;
          buffer.appendString("</").appendString(reader.getLocalName()).appendString(">");
          if (level == 0) {
            return buffer.toString();
          }
          break;
        case XMLStreamConstants.CHARACTERS:
          buffer.appendString(encodeXmlText(reader.getText()));
          break;
        default:
      }
    }
    return null;
  }
}
