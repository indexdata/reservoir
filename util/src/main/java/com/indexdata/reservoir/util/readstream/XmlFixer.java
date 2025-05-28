package org.folio.reservoir.util.readstream;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

public class XmlFixer extends MappingReadStream<Buffer, Buffer> {
  public XmlFixer(ReadStream<Buffer> stream) {
    super(stream, new XmlFixerMapper());
  }

}
