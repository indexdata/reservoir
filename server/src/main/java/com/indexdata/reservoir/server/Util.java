package org.folio.reservoir.server;

import io.vertx.ext.web.validation.RequestParameter;
import io.vertx.ext.web.validation.RequestParameters;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public final class Util {
  private static final String TIME_ZERO = "T00:00:00Z";

  private Util() {
    throw new UnsupportedOperationException("Util");
  }

  static String getParameterString(RequestParameter parameter) {
    return parameter == null ? null : parameter.getString();
  }

  static String getQueryParameter(RequestParameters params, String key) {
    return Util.getParameterString(params.queryParameter(key));
  }

  static String getQueryParameterQuery(RequestParameters params) {
    return getQueryParameter(params, "query");
  }

  /**
   * Parse ISO time.
   * @param s time string
   * @return local date time
   */
  public static LocalDateTime parseIso(String s) {
    return LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME);
  }

  /**
   * Parse OAI "from" parameter and return LocalDateTime.
   * @param from from-string
   * @return LocalDateTime representation
   * @throws OaiException if time could not be parsed
   */
  public static LocalDateTime parseFrom(String from) {
    try {
      if (from.length() == 10) {
        return parseIso(from + TIME_ZERO);
      }
      return parseIso(from);
    } catch (DateTimeParseException e) {
      throw OaiException.badArgument("bad from " + from + ":" + e.getMessage());
    }
  }

  /**
   * Parse OAI "until" parameter and return LocalDateTime.
   * @param until until-string
   * @return parsed time with one unit above (1 second or 1 day)
   * @throws OaiException if time could not be parsed
   */
  public static LocalDateTime parseUntil(String until) {
    try {
      if (until.length() == 10) {
        return parseIso(until + TIME_ZERO).plusDays(1L);
      }
      return parseIso(until).plusSeconds(1L);
    } catch (DateTimeParseException e) {
      throw OaiException.badArgument("bad until " + until + ":" + e.getMessage());
    }
  }

  public static String formatOaiDateTime(LocalDateTime d) {
    return d.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
        .format(DateTimeFormatter.ISO_DATE_TIME);
  }

  /**
   * Get "next" OAI date from datestamp +1 day/second.
   * @param datestamp datestamp in either format.
   * @return datestamp of next
   */
  public static String getNextOaiDate(String datestamp) {
    String res = parseUntil(datestamp).format(DateTimeFormatter.ISO_DATE_TIME);
    if (datestamp.length() == 10) {
      return res.substring(0, 10);
    }
    return res + "Z";
  }

  /**
   * Get current datestamp in UTC without nano-seconds component.
   * @return truncated UTC datetime
   */
  public static LocalDateTime getOaiNow() {
    return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
  }

  /**
   * Returns the number of units, that is DAYS or HOURS if datestamp includes time,
   * between 'now' and 'datestamp' arguments. Negative if 'datestamp' is before now.
   * @param now LocalDateTime that represents "now"
   * @param datestamp datestamp to compare
   * @return number of units between now and the datestamp
   */
  public static long unitsBetween(LocalDateTime now, String datestamp) {
    LocalDateTime ds = parseFrom(datestamp);
    if (datestamp.length() > 10) {
      //we go for HOURS rather than SECONDS or MINUTES to account
      //for potentially long batch processing time on the server
      return ChronoUnit.HOURS.between(now, ds);
    }
    return ChronoUnit.DAYS.between(now, ds);
  }
}
