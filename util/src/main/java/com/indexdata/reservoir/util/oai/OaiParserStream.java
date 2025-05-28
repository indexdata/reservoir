package org.folio.reservoir.util.oai;

import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;
import java.util.function.Consumer;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.folio.reservoir.util.XmlMetadataStreamParser;

public class OaiParserStream<T> {

  private Handler<Throwable> exceptionHandler;

  int level;

  String elem;

  OaiRecord<T> lastRecord;

  final StringBuilder cdata = new StringBuilder();

  String resumptionToken;

  int metadataLevel;

  Consumer<String> handleText;

  int textLevel;

  String errorText;

  String errorCode;

  void setHandleText(Consumer<String> handle) {
    cdata.setLength(0);
    textLevel = level;
    handleText = handle;
  }

  void checkHandleText() {
    if (level >= textLevel) {
      return;
    }
    if (handleText != null) {
      handleText.accept(cdata.toString());
    }
    cdata.setLength(0);
    handleText = null;
  }

  /**
   * Get OAI-PMH resumption token.
   * @return token string
   */
  public String getResumptionToken() {
    return resumptionToken;
  }

  /**
   * Get OAI-PMH error.
   * @return the error
   */
  public String getError() {
    return errorCode != null
      ? errorCode + ": " + errorText
      : errorText;
  }

  public OaiParserStream<T> exceptionHandler(Handler<Throwable> handler) {
    exceptionHandler = handler;
    return this;
  }

  final ReadStream<XMLStreamReader> stream;

  final XmlMetadataStreamParser<T> metadataParser;

  public OaiParserStream(ReadStream<XMLStreamReader> stream,
      XmlMetadataStreamParser<T> metadataParser) {
    this.stream = stream;
    this.metadataParser = metadataParser;
  }

  /**
   * Parse OAI response from stream.
   * @param recordHandler handler that is called for each record
   */
  public void parse(Consumer<OaiRecord<T>> recordHandler) {
    stream.handler(xmlStreamReader -> {
      try {
        int event = xmlStreamReader.getEventType();
        if (event == XMLStreamConstants.END_DOCUMENT) {
          if (lastRecord != null) {
            recordHandler.accept(lastRecord);
          }
        } else if (event == XMLStreamConstants.START_ELEMENT) {
          startElement(recordHandler, metadataParser, xmlStreamReader);
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          endElement(metadataParser, xmlStreamReader);
        } else if (metadataLevel != 0 && level > metadataLevel) {
          metadataParser.handle(xmlStreamReader);
        } else if (event == XMLStreamConstants.CHARACTERS) {
          cdata.append(xmlStreamReader.getText());
        }
      } catch (Exception e) {
        stream.handler(null);
        exceptionHandler.handle(e);
      }
    });
    stream.exceptionHandler(e -> {
      stream.handler(null);
      exceptionHandler.handle(e);
    });
  }

  private void startElement(Consumer<OaiRecord<T>> recordHandler,
      XmlMetadataStreamParser<T> metadataParser, XMLStreamReader xmlStreamReader) {
    level++;
    if (metadataLevel != 0 && level > metadataLevel) {
      metadataParser.handle(xmlStreamReader);
    } else {
      elem = xmlStreamReader.getLocalName();
      if (level == 3 && ("record".equals(elem) || "header".equals(elem))) {
        if (lastRecord != null) {
          recordHandler.accept(lastRecord);
        }
        lastRecord = new OaiRecord<>();
        metadataParser.init();
      }
      if ("header".equals(elem)) {
        for (int i = 0; i < xmlStreamReader.getAttributeCount(); i++) {
          if ("status".equals(xmlStreamReader.getAttributeLocalName(i))
              && "deleted".equals(xmlStreamReader.getAttributeValue(i))) {
            lastRecord.deleted = true;
          }
        }
      } else if ("metadata".equals(elem)) {
        metadataLevel = level;
      } else if ("resumptionToken".equals(elem)) {
        setHandleText(text -> resumptionToken = text);
      } else if ("datestamp".equals(elem)) {
        setHandleText(text -> lastRecord.datestamp = text);
      } else if ("identifier".equals(elem)) {
        setHandleText(text -> lastRecord.identifier = text);
      } else if ("error".equals(elem)) {
        setHandleText(text -> errorText = text);
        errorCode = xmlStreamReader.getAttributeValue(null, "code");
      }
    }
  }

  private void endElement(XmlMetadataStreamParser<T> metadataParser,
      XMLStreamReader xmlStreamReader) {
    level--;
    if (metadataLevel != 0) {
      if (level > metadataLevel) {
        metadataParser.handle(xmlStreamReader);
      } else {
        lastRecord.metadata = metadataParser.result();
        metadataLevel = 0;
      }
    } else {
      checkHandleText();
    }
  }

}
