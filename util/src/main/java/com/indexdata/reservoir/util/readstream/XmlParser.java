package com.indexdata.reservoir.util.readstream;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import javax.xml.stream.XMLStreamReader;

/**
 * Streaming parser for XML ala JsonParser.
 *
 * <p>Parser that reads from stream and emits XMLStreamReader events. Allows for reading
 * large XML structures.
 *
 * @see <a href="https://vertx.io/docs/apidocs/io/vertx/core/parsetools/JsonParser.html">JsonParser</a>
 */
public class XmlParser extends MappingReadStream<XMLStreamReader, Buffer> {

  private XmlParser(ReadStream<Buffer> stream, Mapper<Buffer, XMLStreamReader> mapper,
      BiConsumer<Long, TimeUnit> timingConsumer) {
    super(stream, mapper, timingConsumer);
  }

  public static XmlParser newParser(ReadStream<Buffer> stream,
      BiConsumer<Long, TimeUnit> timingConsumer) {
    return new XmlParser(stream, new XmlMapper(), timingConsumer);
  }

}
