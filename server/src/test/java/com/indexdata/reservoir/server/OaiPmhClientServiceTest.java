package com.indexdata.reservoir.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

import io.vertx.core.json.JsonObject;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class OaiPmhClientServiceTest {
  @Test
  void testParseRetryAfter() {
    assertThat(OaiPmhClientService.parseRetryAfter(null), is(nullValue()));
    assertThat(OaiPmhClientService.parseRetryAfter("invalid"), is(nullValue()));
    assertThat(OaiPmhClientService.parseRetryAfter("0"), is(nullValue()));
    assertThat(OaiPmhClientService.parseRetryAfter("120"), is(120000L));
    // past date
    assertThat(OaiPmhClientService.parseRetryAfter("Tue, 2 Dec 2025 07:28:00 GMT"), is(nullValue()));
    // future date
    ZonedDateTime zdt = ZonedDateTime.now().plusSeconds(2);
    String imfDate = zdt.format(DateTimeFormatter.RFC_1123_DATE_TIME);
    assertThat(OaiPmhClientService.parseRetryAfter(imfDate), lessThanOrEqualTo(2000L));
  }

  @Test
  void testCheckRetryWait400() {
    var e = new OaiPmhClientService.HttpStatusError(400, null, "0");
    Long waitMs = OaiPmhClientService.checkRetryWait(e, new JsonObject().put("waitRetries", 3));
    assertThat(waitMs, is(nullValue()));
  }

  @Test
  void testCheckRetryWait500() {
    var e = new OaiPmhClientService.HttpStatusError(500, null, "0");
    Long waitMs = OaiPmhClientService.checkRetryWait(e, new JsonObject().put("waitRetries", 3));
    assertThat(waitMs, is(3000L));
  }

  @Test
  void testCheckRetryWait503() {
    var e = new OaiPmhClientService.HttpStatusError(503, "60", "0");
    Long waitMs = OaiPmhClientService.checkRetryWait(e, new JsonObject().put("waitRetries", 3));
    assertThat(waitMs, is(60000L));
  }
}
