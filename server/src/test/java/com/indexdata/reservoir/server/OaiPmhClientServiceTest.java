package com.indexdata.reservoir.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

import io.vertx.core.json.JsonObject;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.indexdata.reservoir.server.OaiPmhClientService.HttpStatusError;

class OaiPmhClientServiceTest {
  @Test
  void testParseRetryAfter() {
    var httpStatusError = new HttpStatusError(400, null, "");
    assertThat(httpStatusError.checkRetryStatus(), is(false));

    httpStatusError = new HttpStatusError(500, null, "");
    assertThat(httpStatusError.checkRetryStatus(), is(true));
    assertThat(httpStatusError.checkRetryAfter(), is(nullValue()));

    httpStatusError = new HttpStatusError(500, "4", "");
    assertThat(httpStatusError.checkRetryAfter(), is(4000L));

    // past date
    ZonedDateTime zdt = ZonedDateTime.now().minusSeconds(2);
    String imfDate = zdt.format(DateTimeFormatter.RFC_1123_DATE_TIME);
    httpStatusError = new HttpStatusError(500, imfDate, "");
    assertThat(httpStatusError.checkRetryAfter(), is(nullValue()));

    // future date
    zdt = ZonedDateTime.now().plusSeconds(2);
    imfDate = zdt.format(DateTimeFormatter.RFC_1123_DATE_TIME);
    httpStatusError = new HttpStatusError(500, imfDate, "");
    assertThat(httpStatusError.checkRetryAfter(), lessThanOrEqualTo(2000L));
  }

  @ParameterizedTest
  @CsvSource({
      "400, null, 0, null",
      "408, null, 10, 10000",
      "429, null, 20, 20000",
      "500, null, 1, 1000",
      "502, null, 0, 1",
      "503, null, 0, 1",
      "504, null, 0, 1",
      "503, 120, 0, 120000",
      "503, invalid, 0, 1"
  })
  void testCheckRetryHttpStatusError(int statusCode, String retryAfter, int waitRetries, String expectedWaitMs) {
    var e = new OaiPmhClientService.HttpStatusError(statusCode, retryAfter, "server error");
    Long waitMs = OaiPmhClientService.checkRetryWait(e, new JsonObject().put("waitRetries", waitRetries));
    if ("null".equals(expectedWaitMs)) {
      assertThat(waitMs, is(nullValue()));
    } else {
      try {
        long expected = Long.parseLong(expectedWaitMs);
        assertThat(waitMs, is(expected));
      } catch (NumberFormatException ex) {
        throw new RuntimeException("Invalid test data: expectedWaitMs=" + expectedWaitMs, ex);
      }
    }
  }

  @Test
  void testCheckRetryConnectionClosed() {
    var e = new io.vertx.core.http.HttpClosedException("Connection closed");
    int waitRetries = 3;
    Long waitMs = OaiPmhClientService.checkRetryWait(e, new JsonObject().put("waitRetries", waitRetries));
    assertThat(waitMs, is(3000L));
  }

  @Test
  void testCheckRetryConnectException() {
    var e = new java.net.ConnectException("Connection refused");
    int waitRetries = 3;
    Long waitMs = OaiPmhClientService.checkRetryWait(e, new JsonObject().put("waitRetries", waitRetries));
    assertThat(waitMs, is(3000L));
  }

  @Test
  void testCheckRetryIllegalStateException() {
    var e = new IllegalStateException();
    int waitRetries = 3;
    Long waitMs = OaiPmhClientService.checkRetryWait(e, new JsonObject().put("waitRetries", waitRetries));
    assertThat(waitMs, is(nullValue()));
  }

}
