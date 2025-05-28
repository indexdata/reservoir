package org.folio.reservoir.util.readstream;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.parsetools.JsonParser;
import io.vertx.core.streams.ReadStream;
import java.util.concurrent.atomic.AtomicInteger;

public class LargeJsonReadStream implements ReadStream<JsonObject> {
  private JsonParser parser;
  private AtomicInteger counter = new AtomicInteger();
  private JsonObject topLevel = new JsonObject();
  private boolean isTopLevel = true;
  private final String arrayField;

  /**
   * Create LJRS.
   * @param in input buffer
   * @param arrayField name of the field with collection of objects
   */
  public LargeJsonReadStream(ReadStream<Buffer> in, String arrayField) {
    if (arrayField == null) {
      throw new IllegalArgumentException("Argument 'arrayField' cannot be null");
    }
    this.arrayField = arrayField;
    parser = JsonParser.newParser(in);
  }

  public LargeJsonReadStream(ReadStream<Buffer> in) {
    this(in, "records");
  }

  @Override
  public ReadStream<JsonObject> exceptionHandler(Handler<Throwable> handler) {
    parser.exceptionHandler(handler);
    return this;
  }

  @Override
  public ReadStream<JsonObject> handler(Handler<JsonObject> handler) {
    //we begin in regular mode and collect top-level fields
    //we switch to objectValue mode when arrive at "records"
    parser.handler(event -> {
      switch (event.type()) {
        case START_ARRAY:
          if (arrayField.equals(event.fieldName())) {
            parser.objectValueMode();
            isTopLevel = false;
          }
          break;
        case VALUE:
          if (isTopLevel) {
            topLevel.put(event.fieldName(), event.value());
          } else {
            JsonObject o = event.objectValue();
            counter.incrementAndGet();
            handler.handle(o);
          }
          break;
        default:
          break;
      }
    });
    return this;
  }

  @Override
  public ReadStream<JsonObject> pause() {
    parser.pause();
    return this;
  }

  @Override
  public ReadStream<JsonObject> resume() {
    parser.resume();
    return this;
  }

  @Override
  public ReadStream<JsonObject> fetch(long amount) {
    parser.fetch(amount);
    return this;
  }

  @Override
  public ReadStream<JsonObject> endHandler(Handler<Void> endHandler) {
    parser.endHandler(endHandler);
    return this;
  }

  public int totalCount() {
    return counter.get();
  }

  public JsonObject topLevelObject() {
    return this.topLevel;
  }

}

