package com.indexdata.reservoir.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.indexdata.reservoir.server.OaiPmhClientService.ClaimLostException;
import com.indexdata.reservoir.server.OaiPmhClientService.Harvest;
import com.indexdata.reservoir.server.metrics.IngestMetrics;
import com.indexdata.reservoir.server.metrics.IngestMetricsNop;
import com.indexdata.reservoir.util.SourceId;
import com.indexdata.reservoir.util.oai.OaiRecord;
import io.restassured.RestAssured;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.folio.okapi.common.XOkapiHeaders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class OaiPmhClientJobsTest extends TestBase {
  private static final String START = "<OAI-PMH><ListRecords>";
  private static final String END = "</ListRecords></OAI-PMH>";
  private static final String RECORD = """
      <record><header><identifier>record-1</identifier><datestamp>2024-01-01</datestamp></header>
      <metadata><record xmlns="http://www.loc.gov/MARC21/slim">
      <leader>00000nam a2200000 a 4500</leader></record></metadata></record>
      """;

  private Storage storage;
  private OaiPmhClientService service;
  private HttpServer server;
  private String id;

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  @Before
  public void setUp() {
    storage = new Storage(vertx, TENANT_1, HttpMethod.POST);
    service = new OaiPmhClientService(vertx);
    service.heartbeatInterval = 25;
    id = UUID.randomUUID().toString().substring(0, 16);
  }

  @After
  public void cleanUp() throws Exception {
    await(storage.getPool().preparedQuery("DELETE FROM " + storage.getOaiPmhClientTable()
        + " WHERE id = $1").execute(Tuple.of(id)));
    await(service.httpClient.close());
    if (server != null) {
      await(server.close());
    }
    await(storage.deleteGlobalRecord("record-1", new SourceId(id), 1));
  }

  private void createClient(String url) throws Exception {
    await(storage.getPool().preparedQuery("INSERT INTO " + storage.getOaiPmhClientTable()
            + " (id, config) VALUES ($1, $2)")
        .execute(Tuple.of(id, new JsonObject().put("sourceId", id).put("url", url)
            .put("from", "2020-01-01").put("metadataPrefix", "marc21"))));
  }

  private void serve(Handler<HttpServerRequest> handler) throws Exception {
    server = await(vertx.createHttpServer().requestHandler(handler).listen(0));
    createClient("http://localhost:" + server.actualPort() + "/oai");
  }

  private Row row() throws Exception {
    return await(storage.getPool().preparedQuery("SELECT * FROM " + storage.getOaiPmhClientTable()
        + " WHERE id = $1").execute(Tuple.of(id))).iterator().next();
  }

  private void expire() throws Exception {
    await(storage.getPool().preparedQuery("UPDATE " + storage.getOaiPmhClientTable()
        + " SET lease_until = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - INTERVAL '1 second'"
        + " WHERE id = $1").execute(Tuple.of(id)));
  }

  private Future<Boolean> write(Harvest harvest, boolean delete) {
    JsonObject record = new JsonObject().put("localId", "record-1")
        .put("payload", new JsonObject()).put("delete", delete);
    return storage.ingestGlobalRecord(vertx, new SourceId(id), 1, record,
        List.of(), new IngestMetricsNop(), conn -> service.guardRecord(storage, conn, harvest));
  }

  private void assertLost(Future<?> future) {
    ExecutionException error = assertThrows(ExecutionException.class, () -> await(future));
    assertTrue(error.getCause().toString(), error.getCause() instanceof ClaimLostException);
  }

  @Test
  public void concurrentStartsAndRecoveryFenceOldOwner() throws Exception {
    createClient("http://localhost:1/oai");
    var claims = await(Future.all(service.claimJob(storage, id, false),
        service.claimJob(storage, id, false)));
    Harvest first = claims.resultAt(0);
    Harvest second = claims.resultAt(1);
    assertTrue((first == null) != (second == null));
    Harvest original = first == null ? second : first;
    original.job.setTotalRecords(7L);
    original.job.getConfig().put("resumptionToken", "checkpoint");
    assertTrue(await(service.saveJob(storage, original, false)));
    assertNull(await(service.claimJob(storage, id, false)));
    assertEquals(original.owner, row().getUUID("owner"));

    expire();
    assertLost(write(original, false));
    var recovered = await(Future.all(service.claimJob(storage, id, true),
        service.claimJob(storage, id, true)));
    first = recovered.resultAt(0);
    second = recovered.resultAt(1);
    assertTrue((first == null) != (second == null));
    Harvest replacement = first == null ? second : first;
    assertNotEquals(original.owner, replacement.owner);
    assertEquals(Long.valueOf(7), replacement.job.getTotalRecords());
    assertEquals("checkpoint", replacement.job.getConfig().getString("resumptionToken"));
    assertLost(write(original, false));
    assertTrue(await(write(replacement, false)));
    assertLost(write(original, true));
    original.job.setStatusIdle();
    original.job.getConfig().put("resumptionToken", "stale");
    assertFalse(await(service.saveJob(storage, original, true)));
    assertEquals("running", row().getJsonObject("job").getString("status"));
    assertEquals("checkpoint", row().getJsonObject("config").getString("resumptionToken"));
  }

  @Test
  public void fourRecordFencesCanOverlapWithoutRenewingLease() throws Exception {
    createClient("http://localhost:1/oai");
    Harvest harvest = await(service.claimJob(storage, id, false));
    LocalDateTime lease = row().getLocalDateTime("lease_until");
    Promise<Void> release = Promise.promise();
    List<Future<Void>> entered = new ArrayList<>();
    List<Future<Void>> transactions = new ArrayList<>();
    try {
      for (int i = 0; i < 4; i++) {
        Promise<Void> locked = Promise.promise();
        entered.add(locked.future());
        transactions.add(storage.getPool().withTransaction(conn ->
            service.guardRecord(storage, conn, harvest).compose(ignored -> {
              locked.complete();
              return release.future();
            })));
      }
      // All four must hold the fence before any transaction is allowed to commit.
      await(Future.all(entered));
      transactions.forEach(transaction -> assertFalse(transaction.isComplete()));
      release.complete();
      await(Future.all(transactions));
      assertEquals(lease, row().getLocalDateTime("lease_until"));
    } finally {
      release.tryComplete();
      await(Future.join(transactions));
    }
  }

  @Test
  public void deleteWaitsForAllSharedRecordFences() throws Exception {
    createClient("http://localhost:1/oai");
    Harvest harvest = await(service.claimJob(storage, id, false));
    Promise<Void> firstLocked = Promise.promise();
    Promise<Void> secondLocked = Promise.promise();
    Promise<Void> releaseFirst = Promise.promise();
    Promise<Void> releaseSecond = Promise.promise();
    Future<Void> first = storage.getPool().withTransaction(conn ->
        service.guardRecord(storage, conn, harvest).compose(ignored -> {
          firstLocked.complete();
          return releaseFirst.future();
        }));
    Future<Void> second = storage.getPool().withTransaction(conn ->
        service.guardRecord(storage, conn, harvest).compose(ignored -> {
          secondLocked.complete();
          return releaseSecond.future();
        }));
    try {
      await(Future.all(firstLocked.future(), secondLocked.future()));
      var deleted = storage.getPool().preparedQuery("DELETE FROM " + storage.getOaiPmhClientTable()
          + " WHERE id = $1").execute(Tuple.of(id));
      Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
          await(storage.getPool().query("SELECT count(*) FROM pg_stat_activity"
              + " WHERE wait_event_type = 'Lock' AND query LIKE 'DELETE%oai_pmh_clients%'")
              .execute()).iterator().next().getLong(0) > 0);
      releaseFirst.complete();
      await(first);
      assertFalse(deleted.isComplete());
      releaseSecond.complete();
      await(second);
      assertEquals(1, await(deleted).rowCount());
      assertLost(write(harvest, false));
    } finally {
      releaseFirst.tryComplete();
      releaseSecond.tryComplete();
      await(Future.join(first, second));
    }
  }

  @Test
  public void stopWaitsForRecordCommitAndFencesFurtherWrites() throws Exception {
    createClient("http://localhost:1/oai");
    Harvest harvest = await(service.claimJob(storage, id, false));
    Promise<Void> locked = Promise.promise();
    Promise<Void> release = Promise.promise();
    Future<Boolean> record = storage.ingestGlobalRecord(vertx, new SourceId(id), 1,
        new JsonObject().put("localId", "record-1").put("payload", new JsonObject()),
        List.of(), new IngestMetricsNop(), conn -> service.guardRecord(storage, conn, harvest)
            .compose(ignored -> {
              locked.complete();
              return release.future();
            }));
    try {
      await(locked.future());
      Future<Void> stop = service.stopJob(storage, id, false);
      Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
          await(storage.getPool().query("SELECT count(*) FROM pg_stat_activity"
              + " WHERE wait_event_type = 'Lock' AND query LIKE '%oai_pmh_clients%'")
              .execute()).iterator().next().getLong(0) > 0);
      assertFalse(stop.isComplete());
      release.complete();
      assertTrue(await(record));
      await(stop);
      assertLost(write(harvest, false));
      assertLost(write(harvest, true));
      assertFalse(await(service.saveJob(storage, harvest, false)));
      assertEquals("idle", row().getJsonObject("job").getString("status"));
      assertNull(row().getUUID("owner"));
      assertNull(row().getLocalDateTime("lease_until"));
      assertNotNull(row().getJsonObject("job").getLong("lastRunningTime"));
    } finally {
      release.tryComplete();
    }
  }

  @Test
  public void deletingAndRecreatingClientRejectsOldWorker() throws Exception {
    createClient("http://localhost:1/oai");
    Harvest original = await(service.claimJob(storage, id, false));
    await(storage.getPool().preparedQuery("DELETE FROM " + storage.getOaiPmhClientTable()
        + " WHERE id = $1").execute(Tuple.of(id)));
    assertLost(write(original, false));
    assertFalse(await(service.saveJob(storage, original, false)));
    createClient("http://localhost:2/oai");
    Harvest replacement = await(service.claimJob(storage, id, false));
    assertLost(write(original, false));
    assertFalse(await(service.saveJob(storage, original, true)));
    assertTrue(await(write(replacement, false)));
    assertEquals("http://localhost:2/oai", row().getJsonObject("config").getString("url"));
  }

  @Test
  public void stalledResponseRenewsLeaseAndStopClosesRequest() throws Exception {
    Promise<Void> received = Promise.promise();
    Promise<Void> closed = Promise.promise();
    AtomicInteger requests = new AtomicInteger();
    serve(request -> {
      requests.incrementAndGet();
      request.connection().closeHandler(ignored -> closed.tryComplete());
      received.tryComplete();
    });
    await(service.startJob(vertx, storage, id, false));
    await(received.future());
    UUID owner = row().getUUID("owner");
    await(storage.getPool().preparedQuery("UPDATE " + storage.getOaiPmhClientTable()
        + " SET lease_until = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + INTERVAL '1 second'"
        + " WHERE id = $1").execute(Tuple.of(id)));
    Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
        row().getLocalDateTime("lease_until").isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(2)));
    await(service.startJob(vertx, storage, id, false));
    assertEquals(owner, row().getUUID("owner"));
    assertEquals(1, requests.get());
    await(service.stopJob(storage, id, false));
    await(closed.future());
    assertEquals("idle", row().getJsonObject("job").getString("status"));
  }

  @Test
  public void statusRecoversCheckpointAndConfigUpdateRequiresStop() throws Exception {
    Promise<String> token = Promise.promise();
    serve(request -> {
      token.tryComplete(request.getParam("resumptionToken"));
      request.response().end(START + END);
    });
    Harvest abandoned = await(service.claimJob(storage, id, false));
    abandoned.job.getConfig().put("resumptionToken", "saved-token");
    await(service.saveJob(storage, abandoned, false));
    JsonObject config = row().getJsonObject("config").copy().put("id", id);
    RestAssured.given().header(XOkapiHeaders.TENANT, TENANT_1)
        .contentType("application/json").body(config.encode())
        .put("/reservoir/pmh-clients/" + id).then().statusCode(409);
    expire();
    RestAssured.given().header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/" + id + "/status").then().statusCode(200);
    assertEquals("saved-token", await(token.future()));
    Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
        "idle".equals(row().getJsonObject("job").getString("status")));
    assertNull(row().getUUID("owner"));
    assertFalse(await(service.saveJob(storage, abandoned, false)));
    RestAssured.given().header(XOkapiHeaders.TENANT, TENANT_1)
        .contentType("application/json").body(config.encode())
        .put("/reservoir/pmh-clients/" + id).then().statusCode(204);
  }

  @Test
  public void partialPageFailureDoesNotAdvanceCheckpoint() throws Exception {
    serve(request -> request.response().end(START + RECORD + "<record><broken>" + END));
    await(service.startJob(vertx, storage, id, false));
    Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
        "idle".equals(row().getJsonObject("job").getString("status")));
    assertNotNull(row().getJsonObject("job").getString("error"));
    assertEquals("2020-01-01", row().getJsonObject("config").getString("from"));
    assertNull(row().getUUID("owner"));
  }

  @Test
  public void ingestionFailureStopsParsingAndDrainsPendingWrites() throws Exception {
    await(service.httpClient.close());
    Promise<Boolean> pending = Promise.promise();
    AtomicInteger ingests = new AtomicInteger();
    service = new OaiPmhClientService(vertx) {
      @Override
      Future<Boolean> ingestRecord(Storage storage, OaiRecord<JsonObject> record,
          SourceId source, int version, List<IngestMatcher> matchers,
          IngestMetrics metrics, Harvest harvest) {
        return ingests.incrementAndGet() == 1 ? pending.future() : Future.failedFuture("ingest failed");
      }
    };
    serve(request -> request.response().end(START + RECORD + RECORD + RECORD + END));
    try {
      await(service.startJob(vertx, storage, id, false));
      Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> ingests.get() == 2);
      assertEquals("running", row().getJsonObject("job").getString("status"));
      pending.complete(true);
      Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
          "idle".equals(row().getJsonObject("job").getString("status")));
      assertTrue(row().getJsonObject("job").getString("error").contains("ingest failed"));
      assertEquals("2020-01-01", row().getJsonObject("config").getString("from"));
      assertEquals(2, ingests.get());
      assertEquals(Long.valueOf(1), row().getJsonObject("job").getLong("totalRecords"));
    } finally {
      pending.tryComplete(true);
    }
  }

  @Test
  public void allOperationsWaitForDatabaseUpdates() throws Exception {
    serve(request -> request.response().end(START + END));
    for (String operation : List.of("start", "stop")) {
      if ("stop".equals(operation)) {
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
            "idle".equals(row().getJsonObject("job").getString("status")));
        assertNotNull(await(service.claimJob(storage, id, false)));
      }
      Promise<Void> locked = Promise.promise();
      Promise<Void> release = Promise.promise();
      Future<Void> transaction = storage.getPool().withTransaction(conn ->
          conn.preparedQuery("SELECT id FROM " + storage.getOaiPmhClientTable()
                  + " WHERE id = $1 FOR UPDATE").execute(Tuple.of(id)).compose(rows -> {
                    locked.complete();
                    return release.future();
                  }));
      try {
        await(locked.future());
        var response = webClient.postAbs(OKAPI_URL + "/reservoir/pmh-clients/_all/" + operation)
            .putHeader(XOkapiHeaders.TENANT, TENANT_1).send();
        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() ->
            await(storage.getPool().query("SELECT count(*) FROM pg_stat_activity"
                + " WHERE wait_event_type = 'Lock' AND query LIKE '%oai_pmh_clients%'")
                .execute()).iterator().next().getLong(0) > 0);
        assertFalse(response.isComplete());
        release.complete();
        await(transaction);
        assertEquals(204, await(response).statusCode());
      } finally {
        release.tryComplete();
      }
    }
    assertEquals("idle", row().getJsonObject("job").getString("status"));
  }

  @Test
  public void statusAllRetiresLegacyStoppedJob() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    serve(request -> {
      requests.incrementAndGet();
      request.response().end(START + END);
    });
    await(service.claimJob(storage, id, false));
    await(storage.getPool().preparedQuery("UPDATE " + storage.getOaiPmhClientTable()
        + " SET stop = TRUE, lease_until = NULL WHERE id = $1").execute(Tuple.of(id)));
    RestAssured.given().header(XOkapiHeaders.TENANT, TENANT_1)
        .get("/reservoir/pmh-clients/_all/status").then().statusCode(200);
    assertEquals("idle", row().getJsonObject("job").getString("status"));
    assertNull(row().getUUID("owner"));
    assertEquals(0, requests.get());
  }


  @Test
  public void stopCancelsLongRetryWait() throws Exception {
    await(service.httpClient.close());
    AtomicReference<Harvest> active = new AtomicReference<>();
    service = new OaiPmhClientService(vertx) {
      @Override
      void oaiHarvestLoop(io.vertx.core.Vertx workerVertx, Storage workerStorage,
          Harvest harvest, int retries) {
        active.set(harvest);
        super.oaiHarvestLoop(workerVertx, workerStorage, harvest, retries);
      }
    };
    service.heartbeatInterval = 25;
    AtomicInteger requests = new AtomicInteger();
    serve(request -> {
      requests.incrementAndGet();
      request.response().setStatusCode(503).putHeader("Retry-After", "3600").end("busy");
    });
    await(service.startJob(vertx, storage, id, false));
    Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> requests.get() == 1);
    assertFalse(active.get().done.future().isComplete());
    await(service.stopJob(storage, id, false));
    await(active.get().done.future());
    assertEquals(1, requests.get());
    assertEquals("idle", row().getJsonObject("job").getString("status"));
  }

}
