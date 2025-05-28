package org.folio.reservoir.util;

import javax.xml.stream.XMLStreamReader;

/**
 * Interface for parsing metadata from streaming XML and produce result of any type.
 *
 * @param <T> type for metadata
 */
public interface XmlMetadataStreamParser<T> {

  /**
   * Called when metadata is to be reset (for each record).
   */
  void init();

  /**
   * Called for each XMLStreamReader event.
   * @param stream as another stream, but do NOT call stream.next().
   */
  void handle(XMLStreamReader stream);

  /**
   * Produce metadata record result. Called after all events are emitted.
   * @return metadata of type
   */
  T result();
}
