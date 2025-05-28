package org.folio.reservoir.util.readstream;

import io.vertx.core.Handler;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.folio.reservoir.util.XmlMetadataParserMarcInJson;

/**
 * Converts MARCXML collection to MARC-in-JSON stream.
 */
public class MarcXmlParserToJson implements ReadStream<JsonObject>, Handler<XMLStreamReader> {

  private long demand = Long.MAX_VALUE;
  final ReadStream<XMLStreamReader> stream;
  private Handler<Throwable> exceptionHandler;
  private Handler<JsonObject> eventHandler;
  private Handler<Void> endHandler;
  private int level;

  private boolean ended;
  final XmlMetadataParserMarcInJson parserMarcInJson = new XmlMetadataParserMarcInJson();

  /**
   * Creates stream conversion from MARCXML collection to MARC-in-JSON.
   * @param stream XmlParser stream.
   */
  public MarcXmlParserToJson(ReadStream<XMLStreamReader> stream) {
    this.stream = stream;
    stream.handler(this);
    stream.endHandler(v -> end());
    stream.exceptionHandler(e -> {
      if (exceptionHandler != null) {
        exceptionHandler.handle(e);
      }
    });
  }

  @Override
  public ReadStream<JsonObject> exceptionHandler(Handler<Throwable> handler) {
    exceptionHandler = handler;
    return this;
  }

  @Override
  public void handle(XMLStreamReader xmlStreamReader) {
    try {
      int e = xmlStreamReader.getEventType();
      if (XMLStreamConstants.START_ELEMENT == e) {
        level++;
        String elem = xmlStreamReader.getLocalName();
        if (level < 2) {
          if (!"collection".equals(elem)) {
            throw new DecodeException("Expected <collection> as root tag. Got " + elem);
          }
        } else { // skip collection, but supply record and below
          if (level == 2) {
            if (!"record".equals(elem)) {
              throw new DecodeException("Expected <record> as 2nd-level. Got " + elem);
            }
            parserMarcInJson.init();
          }
          parserMarcInJson.handle(xmlStreamReader);
        }
      } else if (XMLStreamConstants.END_ELEMENT == e) {
        level--;
        if (level >= 1) { // in record and below
          parserMarcInJson.handle(xmlStreamReader);
        }
        if (level == 1) { // record end
          if (demand == 0L) {
            stream.pause();
          } else if (demand != Long.MAX_VALUE) {
            --demand;
            stream.resume();
          }
          if (eventHandler != null) {
            eventHandler.handle(parserMarcInJson.result());
          }
        }
      } else if (level >= 2) { // pass c-data and stuff when in collection/record .
        parserMarcInJson.handle(xmlStreamReader);
      }
    } catch (Exception ex) {
      if (exceptionHandler != null) {
        exceptionHandler.handle(ex);
      }
    }
  }

  @Override
  public ReadStream<JsonObject> handler(Handler<JsonObject> handler) {
    eventHandler = handler;
    return this;
  }

  @Override
  public ReadStream<JsonObject> pause() {
    demand = 0L;
    return checkDemand();
  }

  @Override
  public ReadStream<JsonObject> resume() {
    demand = Long.MAX_VALUE;
    return checkDemand();
  }

  @Override
  public ReadStream<JsonObject> fetch(long l) {
    demand += l;
    if (demand < 0L) {
      demand = Long.MAX_VALUE;
    }
    return checkDemand();
  }

  private ReadStream<JsonObject> checkDemand() {
    if (demand == 0L) {
      stream.pause();
    } else {
      stream.resume();
    }
    return this;
  }

  @Override
  public ReadStream<JsonObject> endHandler(Handler<Void> handler) {
    if (!ended) {
      endHandler = handler;
    }
    return this;
  }

  void end() {
    if (ended) {
      throw new IllegalStateException("Parsing already done");
    }
    ended = true;
    if (endHandler != null) {
      endHandler.handle(null);
    }
  }

}
