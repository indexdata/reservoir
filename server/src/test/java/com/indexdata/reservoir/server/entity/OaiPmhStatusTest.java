package com.indexdata.reservoir.server.entity;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.vertx.core.json.JsonObject;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.Test;

public class OaiPmhStatusTest {

  @Test
  public void testStatus() {
    OaiPmhStatus status = OaiPmhStatus.empty();
    assertThat(status.status(), is(nullValue()));
    status = status.withStatus(OaiPmhJobStatus.RUNNING);
    assertThat(status.status(), is(OaiPmhJobStatus.RUNNING));
    assertThat(status.isRunning(), is(true));
  }

  @Test
  public void dbJsonRoundTrip() {
    JsonObject original = new JsonObject().put("status", "idle");
    OaiPmhStatus status = OaiPmhStatusJson.fromDbJson(original);
    assertThat(status.status(), is(OaiPmhJobStatus.IDLE));
    JsonObject encoded = OaiPmhStatusJson.toDbJson(status);
    assertThat(encoded, is(original));
  }

  @Test
  public void configOnlyInApiJson() {
    OaiPmhStatus status = OaiPmhStatus.empty();
    JsonObject config = new JsonObject().put("id", "myidentifier");
    JsonObject dbJson = OaiPmhStatusJson.toDbJson(status);
    assertThat(dbJson, is(new JsonObject()));
    JsonObject apiJson = OaiPmhStatusJson.toApiJson(status, config);
    assertThat(apiJson, is(new JsonObject().put("config", new JsonObject().put("id", "myidentifier"))));
  }

  @Test
  public void lastActiveTimestamp() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    OaiPmhStatus status1 = OaiPmhStatus.empty().withLastActiveTimestamp(now);
    JsonObject encoded = OaiPmhStatusJson.toDbJson(status1);
    OaiPmhStatus status2 = OaiPmhStatusJson.fromDbJson(encoded);
    assertThat(status2.lastActiveTimestamp(), is(now));
  }

  @Test
  public void lastStartedTimestamp() {
    LocalDateTime now = LocalDateTime.now();
    OaiPmhStatus status1 = OaiPmhStatus.empty().withLastStartedTimestamp(now);
    JsonObject encoded = OaiPmhStatusJson.toDbJson(status1);
    OaiPmhStatus status2 = OaiPmhStatusJson.fromDbJson(encoded);
    assertThat(status2.lastStartedTimestamp(), is(now));
  }

  @Test
  public void lastRecsPerSec() {
    OaiPmhStatus status = OaiPmhStatus.empty().withCalculatedLastRecsPerSec();
    assertThat(status.lastRecsPerSec(), is(nullValue()));
    status = status.withLastRunningTimeMs(0L).withCalculatedLastRecsPerSec();
    assertThat(status.lastRecsPerSec(), is(nullValue()));
    status = status.withLastRunningTimeMs(2L)
        .withLastTotalRecords(30L)
        .withCalculatedLastRecsPerSec();
    assertThat(status.lastRecsPerSec(), is(15000L));
  }

  @Test
  public void readableRunningTime() {
    OaiPmhStatus status = OaiPmhStatus.empty();
    assertThat(OaiPmhStatusJson.toApiJson(status, null).getString("lastRunningTime"), is(nullValue()));
    status = status.withLastRunningTimeMs(1000L * (2 * 24 * 3600 + 3 * 3600L + 4 * 60 + 5));
    JsonObject apiJson = OaiPmhStatusJson.toApiJson(status, null);
    assertThat(apiJson.getString("lastRunningTime"), is("2 days 03 hrs 04 mins 05 secs"));
  }
}
