package com.indexdata.reservoir.util.readstream;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class XmlFixer extends MappingReadStream<Buffer, Buffer> {
  public XmlFixer(ReadStream<Buffer> stream, BiConsumer<Long, TimeUnit> timingConsumer) {
    super(stream, new XmlFixerMapper(), timingConsumer);
  }

}
