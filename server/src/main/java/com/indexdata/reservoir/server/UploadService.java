package org.folio.reservoir.server;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.Pump;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.GenericCompositeFuture;
import org.folio.okapi.common.HttpResponse;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.reservoir.util.readstream.MappingReadStream;
import org.folio.reservoir.util.readstream.MarcJsonToIngestMapper;
import org.folio.reservoir.util.readstream.MarcToJsonParser;
import org.folio.reservoir.util.readstream.MarcXmlParserToJson;
import org.folio.reservoir.util.readstream.XmlFixer;
import org.folio.reservoir.util.readstream.XmlParser;

public class UploadService {

  private static final Logger log = LogManager.getLogger(UploadService.class);
  private static final String UPLOAD_PERMISSIONS_ALLSOURCES = "reservoir-upload.all-sources";
  private static final String UPLOAD_PERMISSIONS_SOURCE_PREFIX = "reservoir-upload.source";

  private Future<IngestStats> uploadPayloadStream(ReadStream<JsonObject> upload,
      IngestWriteStream ingestWriteStream, int queueSize) {
    Promise<IngestStats> promise = Promise.promise();
    upload.endHandler(x -> ingestWriteStream.end(y ->
        promise.tryComplete(ingestWriteStream.stats())));
    upload.exceptionHandler(promise::tryFail);
    ingestWriteStream.exceptionHandler(promise::tryFail);
    Pump.pump(upload, ingestWriteStream, queueSize).start();
    return promise.future();
  }

  /**
   * non-OpenAPI upload records service handler.
   *
   * <p>Does not produce HTTP response in case of failures.
   * @param ctx routing context
   * @return async result
   */
  public Future<Void> uploadRecords(RoutingContext ctx) {
    try {
      enforcePermissionsBySource(ctx);
      IngestParams params = new IngestParams(ctx.request());
      HttpServerRequest request = ctx.request();
      Future<IngestStatsByFile> future;
      if (params.contentType != null && params.contentType.startsWith("multipart/form-data")) {
        request.setExpectMultipart(true);
        List<Future<IngestStats>> futures = new ArrayList<>();
        IngestStatsByFile statsByFile = new IngestStatsByFile();
        request.uploadHandler(upload ->
            futures.add(
              uploadContent(ctx, upload, params, upload.filename(), upload.contentType())
                .onSuccess(statsByFile::addStats))
        );
        Promise<IngestStatsByFile> promise = Promise.promise();
        request.endHandler(e1 ->
            GenericCompositeFuture.all(futures).map(res -> statsByFile).onComplete(promise));
        future = promise.future();
      } else {
        future = uploadContent(ctx, request, params, params.fileName, params.contentType)
            .map(IngestStatsByFile::new);
      }
      return future.onSuccess(res ->
        HttpResponse.responseJson(ctx, 200).end(res.toJson().encode()))
        .mapEmpty();
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  private Future<IngestStats> uploadContent(RoutingContext ctx, ReadStream<Buffer> readStream,
      IngestParams params, String fileName, String contentType) {
    try {
      if (params.raw) {
        Promise<IngestStats> promise = Promise.promise();
        AtomicLong sz = new AtomicLong();
        readStream.handler(x -> sz.addAndGet(x.length()));
        readStream.endHandler(end -> {
          log.info("Total size {}", sz.get());
          promise.complete();
        });
        return promise.future();
      }
      Storage storage = new Storage(ctx);
      int queueSize = storage.pool.getPoolOptions().getMaxSize() * 10;
      log.info("{} uploading. {} queuSize: {} tenant: {}",
          params.getSummary(fileName), params.getDetails(contentType),
          queueSize, storage.getTenant());
      return uploadContent(readStream,
          new IngestWriteStream(ctx.vertx(), storage, params, fileName, contentType),
          contentType, queueSize, params.xmlFixing);
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
  }

  private Future<IngestStats> uploadContent(ReadStream<Buffer> request,
      IngestWriteStream ingestWriteStream, String contentType,
      int queueSize, boolean xmlFixing) {
    ReadStream<JsonObject> parser;
    if (contentType == null) {
      contentType = "application/octet-stream";
    }
    switch (contentType) {
      case "application/octet-stream", "application/marc" ->
          parser = new MarcToJsonParser(request);
      case "application/xml", "text/xml" ->
          parser = new MarcXmlParserToJson(
              XmlParser.newParser(xmlFixing ? new XmlFixer(request) : request));
      default -> {
        return Future.failedFuture("Unsupported content-type: " + contentType);
      }
    }
    parser = new MappingReadStream<>(parser, new MarcJsonToIngestMapper());
    return uploadPayloadStream(parser, ingestWriteStream, queueSize);
  }

  private String enforcePermissionsBySource(RoutingContext ctx) {
    String sourceId = IngestParams.validateSourceId(ctx.request());
    try {
      Set<String> perms = parsePermissions(ctx);
      if (perms.contains(UPLOAD_PERMISSIONS_ALLSOURCES)) {
        return UPLOAD_PERMISSIONS_ALLSOURCES;
      }
      String perm = UPLOAD_PERMISSIONS_SOURCE_PREFIX + "." + sourceId;
      if (perms.contains(perm)) {
        return perm;
      }
    } catch (Exception e) {
      throw new ForbiddenException("Cannot verify permissions to upload records for source '"
      + sourceId + "'", e);
    }
    throw new ForbiddenException("Insufficient permissions to upload records for source '"
        + sourceId + "'");
  }

  @SuppressWarnings("unchecked")
  private static Set<String> parsePermissions(RoutingContext ctx) {
    Set<String> perms = new HashSet<>();
    String permsHeader = ctx.request().getHeader(XOkapiHeaders.PERMISSIONS);
    if (permsHeader == null || permsHeader.isEmpty()) {
      return perms;
    }
    JsonArray permsArray = new JsonArray(permsHeader);
    perms.addAll(permsArray.getList());
    return perms;
  }
}
