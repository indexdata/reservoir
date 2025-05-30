package com.indexdata.reservoir.client;

import com.indexdata.reservoir.util.AsyncCodec;
import com.indexdata.reservoir.util.IngestRecord;
import com.indexdata.reservoir.util.SourceId;
import com.indexdata.reservoir.util.XmlSerializer;
import io.vertx.core.Expectation;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpResponseExpectation;
import io.vertx.core.http.HttpResponseHead;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.GenericCompositeFuture;
import org.folio.okapi.common.XOkapiHeaders;
import org.marc4j.MarcPermissiveStreamReader;
import org.marc4j.MarcReader;
import org.marc4j.MarcStreamReader;
import org.marc4j.MarcXmlWriter;
import org.marc4j.converter.impl.AnselToUnicode;

@java.lang.SuppressWarnings({"squid:S106"})
public class Client {
  static final Logger log = LogManager.getLogger(Client.class);

  SourceId sourceId;
  MultiMap headers = MultiMap.caseInsensitiveMultiMap();
  int chunkSize = 1;
  int offset;
  int currentOffset;
  int limit;
  boolean echo = false;
  boolean compress = false;
  boolean strict = false;
  Integer localSequence = 0;
  WebClient webClient;
  Vertx vertx;
  TransformerFactory transformerFactory = TransformerFactory.newInstance();

  List<Templates> templates = new LinkedList<>();

  Expectation<HttpResponseHead> errorExpectation =
      HttpResponseExpectation.SC_SUCCESS.wrappingFailure((resp, err) -> {
        HttpResponse<?> response = (HttpResponse<?>) resp;
        return new IOException(
          String.format("%1d %2s", resp.statusCode(), response.bodyAsString()));
      });

  /**
   * Construct the client.
   */
  public Client(Vertx vertx) {
    this.vertx = vertx;
    initWebClient(HttpVersion.HTTP_1_1);
    headers.set(XOkapiHeaders.URL, System.getenv("OKAPI_URL"));
    headers.set(XOkapiHeaders.TOKEN, System.getenv("OKAPI_TOKEN"));
    headers.set(XOkapiHeaders.TENANT, System.getenv("OKAPI_TENANT"));
  }

  Client(Vertx vertx, String url, String token, String tenant) {
    this(vertx);
    headers.set(XOkapiHeaders.URL, url);
    headers.set(XOkapiHeaders.TOKEN, token);
    headers.set(XOkapiHeaders.TENANT, tenant);
  }

  private void initWebClient(HttpVersion version) {
    WebClientOptions webClientOptions = new WebClientOptions()
        .setProtocolVersion(version);
    webClient = WebClient.create(vertx, webClientOptions);
  }

  /**
   * Reconfigures underlying WebClient instance.
   * instance with HTTP/2
   * @return this client instance for fluency
   */
  public Client asHttp2Client() {
    close();
    initWebClient(HttpVersion.HTTP_2);
    return this;
  }

  public void setSourceId(String sourceId) {
    this.sourceId = new SourceId(sourceId);
  }

  public void setChunkSize(int chunkSize) {
    this.chunkSize = chunkSize;
  }

  public void setLimit(int limit) {
    this.limit = limit;
  }

  public void setOffset(int offset) {
    this.offset = offset;
  }

  public void setEcho() {
    this.echo = true;
  }

  public void setCompress() {
    this.compress = true;
  }

  public void setStrict() {
    this.strict = true;
  }

  private void incrementSequence() {
    ++localSequence;
    if (!echo && (localSequence % 1000) == 0) {
      log.info("{}", localSequence);
    }
  }

  private boolean belowLimit() {
    return limit <= 0 || currentOffset < offset + limit;
  }

  private interface ReaderProxy {
    boolean hasNext() throws IOException;

    boolean readNext() throws IOException;

    String parseNext() throws IOException;
  }

  private class MarcReaderProxy implements ReaderProxy {
    private final MarcReader marcReader;
    private org.marc4j.marc.Record marcRecord;

    public MarcReaderProxy(MarcReader reader) {
      if (reader == null) {
        throw new IllegalArgumentException("Argument reader cannot be null");
      }
      marcReader = reader;
    }

    public boolean hasNext() {
      return marcReader.hasNext();
    }

    public boolean readNext() {
      marcRecord = marcReader.next();
      return true;
    }

    public String parseNext() {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      MarcXmlWriter writer = new MarcXmlWriter(out);
      char charCodingScheme = marcRecord.getLeader().getCharCodingScheme();
      if (charCodingScheme == ' ') {
        //ansel converter already escapes non-XML chars
        marcRecord.getLeader().setCharCodingScheme('a');
        writer.setConverter(new AnselToUnicode());
      }
      // we need to additionally strip non-XML chars
      writer.setCheckNonXMLChars(true);
      writer.write(marcRecord);
      writer.close();
      return out.toString();
    }
  }

  Future<JsonObject> createIngestRecordT(String marcXml, List<Templates> templates) {
    return vertx.executeBlocking(() -> IngestRecord.createIngestRecord(marcXml, templates), false);
  }

  private class XmlReaderProxy implements ReaderProxy {
    private final XMLStreamReader xmlReader;
    private int xmlEvent;

    public XmlReaderProxy(XMLStreamReader reader) {
      if (reader == null) {
        throw new IllegalArgumentException("Argument reader cannot be null");
      }
      xmlReader = reader;
    }

    public boolean hasNext() throws IOException {
      try {
        return xmlReader.hasNext();
      } catch (XMLStreamException xse) {
        throw new IOException(xse);
      }
    }

    public boolean readNext() throws IOException {
      try {
        while (xmlReader.hasNext()) {
          xmlEvent = xmlReader.next();
          if (xmlEvent == XMLStreamConstants.START_ELEMENT
              && "record".equals(xmlReader.getLocalName())) {
            return true;
          }
        }
        return false;
      } catch (XMLStreamException xse) {
        throw new IOException(xse);
      }
    }

    public String parseNext() throws IOException {
      try {
        return XmlSerializer.get(xmlEvent, xmlReader);
      } catch (XMLStreamException xse) {
        throw new IOException(xse);
      }
    }
  }

  private void sendChunk(ReaderProxy reader, Promise<Void> promise) {
    List<Future<JsonObject>> futures = new ArrayList<>(chunkSize);
    try {
      while (belowLimit() && reader.hasNext() && futures.size() < chunkSize) {
        if (reader.readNext()) {
          if (currentOffset++ < offset) {
            continue; //skip to offset
          }
          String rec = reader.parseNext();
          futures.add(createIngestRecordT(rec, templates));
          incrementSequence();
        }
      }
    } catch (Exception e) {
      log.info("Failed offset (resume at): {}", currentOffset - 1);
      promise.fail(e);
      return;
    }
    GenericCompositeFuture.all(futures)
        .onFailure(e -> {
          log.error(e.getMessage(), e);
          promise.complete();
        })
        .onSuccess(resultingRecords -> {
          JsonArray records = new JsonArray();
          List<JsonObject> list = resultingRecords.list();
          list.forEach(records::add);
          JsonObject request = new JsonObject()
              .put("sourceId", sourceId.toString())
              .put("records", records);

          if (futures.isEmpty()) {
            if (!echo) {
              log.info("{}", localSequence);
              log.info("Next offset (resume at): {}", currentOffset);
            }
            promise.complete();
            return;
          }
          if (echo) {
            System.out.println(request);
            vertx.runOnContext(x -> sendChunk(reader, promise));
          } else {
            Future<Buffer> futureBuffer = compress
                ? AsyncCodec.compress(vertx, request.toBuffer())
                : Future.succeededFuture(request.toBuffer());
            futureBuffer.compose(b ->
                    webClient.putAbs(headers.get(XOkapiHeaders.URL) + "/reservoir/records")
                        .putHeaders(headers)
                        .putHeader(HttpHeaders.CONTENT_TYPE.toString(), "application/json")
                        .putHeader(HttpHeaders.CONTENT_ENCODING.toString(),
                            compress ? "gzip" : null)
                        .sendBuffer(b)
                        .expecting(errorExpectation))
                        .expecting(HttpResponseExpectation.JSON)
                .onFailure(e -> {
                  log.info("Failed offset (resume at): {}", currentOffset - 1);
                  promise.fail(e);
                })
                .onSuccess(x -> sendChunk(reader, promise));
          }
        });
  }

  /**
   * Initialize data for the tenant.
   * @return async result
   */
  public Future<Void> init() {
    JsonObject request = new JsonObject()
        .put("module_to", "mod-reservoir-1.0.0");
    return tenantOp(request);
  }

  /**
   * Initialize data for the tenant.
   * @return async result
   */
  public Future<Void> purge() {
    JsonObject request = new JsonObject()
        .put("purge", Boolean.TRUE)
        .put("module_to", "mod-reservoir-1.0.0");
    return tenantOp(request);
  }

  private Future<Void> tenantOp(JsonObject request) {
    String okapiUrl = headers.get(XOkapiHeaders.URL);
    return webClient.postAbs(okapiUrl + "/_/tenant")
        .putHeaders(headers)
        .sendJsonObject(request).compose(res -> {
          if (res.statusCode() == 204 || res.statusCode() == 200) {
            return Future.succeededFuture();
          } else if (res.statusCode() != 201) {
            throw new ClientException("For /_/tenant got status code " + res.statusCode());
          }
          String id = res.bodyAsJsonObject().getString("id");
          return webClient.getAbs(okapiUrl + "/_/tenant/" + id + "?wait=10000")
              .putHeaders(headers)
              .send()
              .expecting(HttpResponseExpectation.SC_OK)
              .expecting(HttpResponseExpectation.JSON)
              .compose(res2 -> {
                if (Boolean.FALSE.equals(res2.bodyAsJsonObject().getBoolean("complete"))) {
                  throw new ClientException("Incomplete job");
                }
                String error = res2.bodyAsJsonObject().getString("error");
                if (error != null) {
                  return Future.failedFuture(error);
                }
                return Future.succeededFuture();
              })
              .compose(x ->
                webClient.deleteAbs(okapiUrl + "/_/tenant/" + id)
                    .putHeaders(headers)
                    .send()
                    .expecting(HttpResponseExpectation.SC_NO_CONTENT)
                    .mapEmpty()
              );
        });
  }

  Future<Void> sendIso2709(InputStream stream) {
    return Future.<Void>future(p -> sendChunk(
      new MarcReaderProxy(
        strict
          ? new MarcStreamReader(stream)
          : new MarcPermissiveStreamReader(stream, true, true)),
        p))
        .eventually(() -> {
          try {
            stream.close();
            return Future.succeededFuture();
          } catch (IOException e) {
            return Future.failedFuture(e);
          }
        });
  }

  Future<Void> sendMarcXml(InputStream stream) throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    XMLStreamReader xmlStreamReader = factory.createXMLStreamReader(stream);
    return Future.<Void>future(p -> sendChunk(new XmlReaderProxy(xmlStreamReader), p))
        .eventually(() -> {
          try {
            stream.close();
            return Future.succeededFuture();
          } catch (IOException e) {
            return Future.failedFuture(e);
          }
        });
  }

  /**
   * Send file to shared-index server.
   * @param fname filename
   * @return async result
   */
  @java.lang.SuppressWarnings({"squid:S2095"}) // Resources should be closed
  // stream.close in eventually , *not* in finally as that would premature close the stream.
  public Future<Void> sendFile(String fname) {
    if (sourceId == null) {
      return Future.failedFuture("source identifier must be given");
    }
    try {
      InputStream stream = new FileInputStream(fname);
      if (fname.endsWith(".rec") || fname.endsWith(".marc") || fname.endsWith(".mrc")) {
        return sendIso2709(stream);
      } else if (fname.endsWith(".xml")) {
        return sendMarcXml(stream);
      } else {
        stream.close();
        return Future.failedFuture("filename '" + fname + "' must be end with"
            + " .xml (marcxml) or .rec (iso2709)");
      }
    } catch (XMLStreamException | IOException e) {
      return Future.failedFuture(e);
    }
  }

  /**
   * Add XSLT to be used for each record.
   * @param fname filename of XSL stylesheet
   * @return async result
   */
  public Future<Void> setXslt(String fname) {
    try {
      Source xslt = new StreamSource(fname);
      templates.add(transformerFactory.newTemplates(xslt));
      return Future.succeededFuture();
    } catch (TransformerConfigurationException e) {
      return Future.failedFuture(e);
    }
  }

  private static String getArgument(String [] args, int i) {
    if (i >= args.length) {
      throw new ClientException("Missing argument for option '" + args[i - 1] + "'");
    }
    return args[i];
  }

  /**
   * Close underlying WebClient instance.
   */
  public void close() {
    webClient.close();
  }

  private static void printGitInfo(Class<Client> clazz) {
    String appName = "Reservoir CLI";
    InputStream in = clazz.getClassLoader().getResourceAsStream("git.properties");
    if (in != null) {
      try {
        Properties prop = new Properties();
        prop.load(in);
        in.close();
        String gitCommitIdAbbrev = prop.getProperty("git.commit.id.abbrev");
        String gitCommitTime = prop.getProperty("git.commit.time");
        System.out.printf("%s %s (%s)%n", appName, gitCommitIdAbbrev, gitCommitTime);
      } catch (IOException ioe) {
        //ignore
      }
    }
  }

  /** Execute command line shared-index client.
   *
   * @param vertx Vertx. handcle
   * @param args command line args
   * @return async result
   */
  public static Future<Void> exec(Vertx vertx, String[] args) {
    Client client = new Client(vertx);
    return exec(client, args)
        .onComplete(x -> client.close());
  }

  static Future<Void> exec(Client client, String[] args) {
    try {
      Future<Void> future = Future.succeededFuture();
      int i = 0;
      while (i < args.length) {
        String arg;
        if (args[i].startsWith("--")) {
          switch (args[i].substring(2)) {
            case "help":
              printGitInfo(Client.class);
              System.out.println("[options] [file..]");
              System.out.println(" --source sourceId   (ISIL string, mandatory)");
              System.out.println(" --chunk sz          (defaults to 1)");
              System.out.println(" --offset int        (defaults to 0)");
              System.out.println(" --limit int         (defaults to 0 - no limit)");
              System.out.println(" --xsl file          (xslt transform for inventory payload)");
              System.out.println(" --echo              (only output result)");
              System.out.println(" --compress          (compress requests with gzip)");
              System.out.println(" --http2             (use HTTP/2)");
              System.out.println(" --strict            (strict marc parsing, off by default)");
              System.out.println(" --init");
              System.out.println(" --purge");
              break;
            case "source":
              arg = getArgument(args, ++i);
              client.setSourceId(arg);
              break;
            case "chunk":
              arg = getArgument(args, ++i);
              client.setChunkSize(Integer.parseInt(arg));
              break;
            case "offset":
              arg = getArgument(args, ++i);
              client.setOffset(Integer.parseInt(arg));
              break;
            case "limit":
              arg = getArgument(args, ++i);
              client.setLimit(Integer.parseInt(arg));
              break;
            case "echo":
              client.setEcho();
              break;
            case "compress":
              client.setCompress();
              break;
            case "strict":
              client.setStrict();
              break;
            case "http2":
              client.asHttp2Client();
              break;
            case "xsl":
              arg = getArgument(args, ++i);
              future = future.compose(x -> client.setXslt(arg));
              break;
            case "init":
              future = future.compose(x -> client.init());
              break;
            case "purge":
              future = future.compose(x -> client.purge());
              break;
            default:
              throw new ClientException("Unsupported option: '" + args[i] + "'");
          }
        } else {
          arg = args[i];
          if (!client.echo) {
            printGitInfo(Client.class);
            log.info("Using: offset {} limit {} chunk {}",
                client.offset, client.limit, client.chunkSize);
          }
          future = future.compose(x -> client.sendFile(arg));
          //break here otherwise args that follow will be ignored in the first call to sendChunk
          break;
        }
        i++;
      }
      return future;
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }
}
