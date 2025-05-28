package org.folio.reservoir.server;

import org.folio.okapi.testing.UtilityClassTester;
import org.junit.Assert;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public class UtilTest {

  @Test
  public void isUtilityClass() {
    UtilityClassTester.assertUtilityClass(Util.class);
  }

  @Test
  public void dateParseFrom() {
    Assert.assertEquals("2022-04-12T00:00", Util.parseFrom("2022-04-12").toString());
    Assert.assertEquals("2022-04-12T10:20:30", Util.parseFrom("2022-04-12T10:20:30Z").toString());
    Assert.assertEquals("badArgument", Assert.assertThrows(OaiException.class,
        () -> Util.parseFrom("2022-04x")).getErrorCode());
  }

  @Test
  public void dateParseUntil() {
    Assert.assertEquals("2022-04-13T00:00", Util.parseUntil("2022-04-12").toString());
    Assert.assertEquals("2022-04-12T10:20:31", Util.parseUntil("2022-04-12T10:20:30Z").toString());
    Assert.assertEquals("badArgument", Assert.assertThrows(OaiException.class,
        () -> Util.parseUntil("2022-04x")).getErrorCode());
  }

  @Test
  public void datestampToFrom() {
    Assert.assertEquals("2022-04-13", Util.getNextOaiDate("2022-04-12"));
    Assert.assertEquals("2022-04-12T10:20:31Z", Util.getNextOaiDate("2022-04-12T10:20:30Z"));
    Assert.assertEquals("badArgument", Assert.assertThrows(OaiException.class,
        () -> Util.getNextOaiDate("2022-04x")).getErrorCode());
  }

  @Test
  public void wholeminute() {
    LocalDateTime d = LocalDateTime.of(2022, 4, 20, 16, 0, 0, 900000000);
    Assert.assertEquals("2022-04-20T16:00:00Z", Util.formatOaiDateTime(d));
  }

  @Test
  public void unitsBetween() {
    LocalDateTime now = LocalDateTime.of(2022, 7, 1, 11, 0, 0);
    LocalDateTime nowNanoBefore = LocalDateTime.of(2022, 7, 1, 10, 59, 59, 999999999);
    LocalDateTime nowNanoAfter = LocalDateTime.of(2022, 7, 1, 11, 0, 0, 1);
    LocalDateTime nowNanosAfter = LocalDateTime.of(2022, 7, 1, 11, 0, 0, 999999999);
    LocalDateTime nowNanosBefore = LocalDateTime.of(2022, 7, 1, 10, 59, 59, 1);
    String datestampSameDay = "2022-07-01";
    String datestampSameTime = "2022-07-01T11:00:00";
    String datestampDayBefore = "2022-06-30";
    String datestampSecondBefore = "2022-07-01T10:59:59";
    String datestamp10DaysBefore = "2022-06-21";
    String datestamp10SecondsBefore = "2022-07-01T10:59:50";
    String datestampMinuteBefore = "2022-07-01T10:59:00";
    String datestampHourBefore = "2022-07-01T10:00:00";

    Assert.assertEquals(0, Util.unitsBetween(now, datestampSameDay));
    Assert.assertEquals(0, Util.unitsBetween(now, datestampSameTime));
    Assert.assertEquals(0, Util.unitsBetween(nowNanoBefore, datestampSameTime));
    Assert.assertEquals(0, Util.unitsBetween(nowNanoAfter, datestampSameTime));
    Assert.assertEquals(0, Util.unitsBetween(nowNanosBefore, datestampSameTime));
    Assert.assertEquals(0, Util.unitsBetween(nowNanosAfter, datestampSameTime));
    Assert.assertEquals(-1, Util.unitsBetween(now, datestampDayBefore));
    Assert.assertEquals(0, Util.unitsBetween(now, datestampSecondBefore));
    Assert.assertEquals(-10, Util.unitsBetween(now, datestamp10DaysBefore));
    Assert.assertEquals(0, Util.unitsBetween(now, datestamp10SecondsBefore));
    Assert.assertEquals(0, Util.unitsBetween(now, datestampMinuteBefore));
    Assert.assertEquals(-1, Util.unitsBetween(now, datestampHourBefore));
  }

  @Test
  public void getOaiNow() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    LocalDateTime oaiNow = Util.getOaiNow();
    Assert.assertEquals(now, oaiNow);
  }


}
