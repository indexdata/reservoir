package com.indexdata.reservoir.util.readstream;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.marc4j.marc.Record;

public class MarcToJsonParser extends MappingReadStream<JsonObject, Record> {

  /** Read MARC and convert to JSON-in-MARC. */
  public MarcToJsonParser(ReadStream<Buffer> stream, BiConsumer<Long, TimeUnit> timingConsumer) {
    super(
        new MappingReadStream<>(stream, new Marc4jMapper(), timingConsumer),
        new MarcToJsonObjectMapper(),
        timingConsumer);
  }

}
