package org.folio.reservoir.util.readstream;

import com.fasterxml.aalto.AsyncByteArrayFeeder;
import com.fasterxml.aalto.AsyncXMLInputFactory;
import com.fasterxml.aalto.AsyncXMLStreamReader;
import com.fasterxml.aalto.stax.InputFactoryImpl;
import io.vertx.core.buffer.Buffer;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Stream mapper based on <a href="https://github.com/FasterXML/aalto-xml">Aalto XML</a>.
 */
public class XmlMapper
    implements Mapper<Buffer, XMLStreamReader> {
  private final AsyncXMLStreamReader<AsyncByteArrayFeeder> parser;

  private boolean ended;

  XmlMapper() {
    AsyncXMLInputFactory factory = new InputFactoryImpl();
    parser = factory.createAsyncForByteArray();
  }

  @Override
  public XMLStreamReader poll() {
    try {
      if (parser.hasNext() && parser.next() != AsyncXMLStreamReader.EVENT_INCOMPLETE) {
        return parser;
      }
      // even though we have told the parse of endOfInput, it still does not throw an
      // error when on incomplete input, so we have to make that check ourselves.
      if (ended && parser.getEventType() == AsyncXMLStreamReader.EVENT_INCOMPLETE) {
        throw new XMLStreamException("Incomplete input", parser.getLocation());
      }
      return null;
    } catch (XMLStreamException e) {
      throw new XmlMapperException(e);
    }
  }

  @Override
  public void end() {
    ended = true;
    parser.getInputFeeder().endOfInput();
  }

  @Override
  public void push(Buffer buffer) {
    byte[] bytes = buffer.getBytes();
    try {
      parser.getInputFeeder().feedInput(bytes, 0, bytes.length);
    } catch (XMLStreamException e) {
      throw new XmlMapperException(e);
    }
  }

}
