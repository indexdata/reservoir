package org.folio.reservoir.server;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

public class ResumptionToken {
  private final String set;
  private LocalDateTime from;
  private UUID id;
  private final String until;

  /**
   * Create token with set and until.
   * @param set set in use (never null)
   * @param until until (null if not given)
   */
  public ResumptionToken(String set, String until) {
    this.set = set;
    this.until = until;
  }

  /**
   * Create resumption from coded string.
   * @param coded coded string
   */
  public ResumptionToken(String coded) {
    String s = new String(Base64.getDecoder().decode(coded));
    int i1 = s.indexOf(' ');
    int i2 = s.indexOf(' ', i1 + 1);
    if (i1 == -1 || i2 == -1) {
      throw new IllegalArgumentException("Bad resumptiontoken");
    }
    int islash = s.indexOf('/'); // from date does not contain slash
    if (islash > 0 && islash < i1) {
      id = UUID.fromString(s.substring(islash + 1, i1));
    } else {
      islash = i1;
    }
    from = Util.parseIso(s.substring(0, islash));
    String tmp = s.substring(i1 + 1, i2);
    until = tmp.equals("null") ? null : tmp;
    set = s.substring(i2 + 1);
  }

  /**
   * Return encoded token value.
   * @return encoded token string
   */
  public String encode() {
    if (from == null) {
      throw new IllegalStateException("from unset");
    }
    String s = from + (id != null ? "/" + id : "") + " " + until + " " + set;
    return Base64.getEncoder().encodeToString(s.getBytes());
  }

  void setFrom(LocalDateTime from) {
    this.from = from;
  }

  public LocalDateTime getFrom() {
    return from;
  }

  void setId(UUID id) {
    this.id = id;
  }

  public UUID getId() {
    return id;
  }

  public String getUntil() {
    return until;
  }

  public String getSet() {
    return set;
  }

  public String toString() {
    return "set=" + set + " from=" + (from != null ? from.toString() : "null")
        + " id=" + id + " until=" + until;
  }
}
