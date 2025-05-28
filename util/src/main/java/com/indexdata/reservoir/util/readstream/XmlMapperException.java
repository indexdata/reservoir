package org.folio.reservoir.util.readstream;

import javax.xml.stream.XMLStreamException;

public class XmlMapperException extends RuntimeException {

  public XmlMapperException(XMLStreamException e) {
    super(e.getMessage(), e);
  }
}
