package org.folio.reservoir.util.readstream;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import org.marc4j.marc.Record;

public class MarcToJsonParser extends MappingReadStream<JsonObject, Record> {


  public MarcToJsonParser(ReadStream<Buffer> stream) {
    super(new MappingReadStream<>(stream, new Marc4jMapper()), new MarcToJsonObjectMapper());
  }

}
