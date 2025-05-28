package org.folio.reservoir.server.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.vertx.core.json.JsonObject;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OaiPmhStatus {
  @JsonIgnore
  private JsonObject config;

  String error;

  LocalDateTime lastActiveTimestamp;

  Long lastRecsPerSec;

  LocalDateTime lastStartedTimestamp;

  Long lastRunningTime;

  Long lastTotalRecords;

  String status;

  Long totalDeleted;

  private Long totalInserted;

  private Long totalUpdated;

  Long totalRecords;

  Integer totalRequests;

  public String getError() {
    return error;
  }

  public void setError(String error) {
    this.error = error;
  }

  /**
   * Get last active timestamp as string.
   * @return time in UTC
   */
  public String getLastActiveTimestamp() {
    return  lastActiveTimestamp != null
        ? lastActiveTimestamp.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)
        : null;
  }

  /**
   * Set last active timestamp as string.
   * @param timestamp time in UTC
   */
  public void setLastActiveTimestamp(String timestamp) {
    this.lastActiveTimestamp = LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME);
  }

  @JsonIgnore
  public LocalDateTime getLastActiveTimestampRaw() {
    return lastActiveTimestamp;
  }

  @JsonIgnore
  public void setLastActiveTimestampRaw(LocalDateTime timestamp) {
    this.lastActiveTimestamp = timestamp;
  }

  public Long getLastRecsPerSec() {
    return lastRecsPerSec;
  }

  public void setLastRecsPerSec(Long lastRecsPerSec) {
    this.lastRecsPerSec = lastRecsPerSec;
  }

  /**
   * Set lastRecsPerSec based on lastRunningTime and lastTotalRecords.
   */
  @JsonIgnore
  public void calculateLastRecsPerSec() {
    lastRecsPerSec = lastRunningTime != null && lastRunningTime > 0L
        ? lastTotalRecords * 1000L / lastRunningTime
        : null;
  }

  /**
   * Get last started timestamp as string.
   * @return time in UTC
   */
  public String getLastStartedTimestamp() {
    return lastStartedTimestamp != null
        ? lastStartedTimestamp.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)
        : null;
  }

  /**
   * Set last started timestamp as string.
   * @param timestamp time in UTC.
   */
  public void setLastStartedTimestamp(String timestamp) {
    this.lastStartedTimestamp = LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_DATE_TIME);
  }

  @JsonIgnore
  public LocalDateTime getLastStartedTimestampRaw() {
    return lastStartedTimestamp;
  }

  @JsonIgnore
  public void setLastStartedTimestampRaw(LocalDateTime timestamp) {
    this.lastStartedTimestamp = timestamp;
  }


  public Long getLastRunningTime() {
    return lastRunningTime;
  }

  public void setLastRunningTime(Long lastRunningTime) {
    this.lastRunningTime = lastRunningTime;
  }

  public Long getLastTotalRecords() {
    return lastTotalRecords;
  }

  public void setLastTotalRecords(Long lastTotalRecords) {
    this.lastTotalRecords = lastTotalRecords;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  @JsonIgnore
  public void setStatusIdle() {
    setStatus("idle");
  }

  @JsonIgnore
  public void setStatusRunning() {
    setStatus("running");
  }

  @JsonIgnore
  public boolean isRunning() {
    return "running".equals(status);
  }

  public Long getTotalDeleted() {
    return totalDeleted;
  }

  public void setTotalDeleted(Long totalDeleted) {
    this.totalDeleted = totalDeleted;
  }

  public Long getTotalRecords() {
    return totalRecords;
  }

  public void setTotalRecords(Long totalRecords) {
    this.totalRecords = totalRecords;
  }

  public Integer getTotalRequests() {
    return totalRequests;
  }

  public void setTotalRequests(Integer totalRequests) {
    this.totalRequests = totalRequests;
  }

  @JsonIgnore
  public JsonObject getConfig() {
    return config;
  }

  @JsonIgnore
  public void setConfig(JsonObject config) {
    this.config = config;
  }

  /**
   * Get JSON object as it's returned by status.
   * @return JSON object
   */
  @JsonIgnore
  public JsonObject getJsonObject() {
    JsonObject o = JsonObject.mapFrom(this)
        .put("config", config);
    if (lastRunningTime != null) {
      long s = lastRunningTime / 1000L;
      String readable = String.format("%d days %02d hrs %02d mins %02d secs",
          s / (24 * 3600), (s % (24 * 3600)) / 3600, (s % 3600) / 60, (s % 60));
      o.put("lastRunningTime", readable);
    }
    return o;
  }

  public Long getTotalInserted() {
    return totalInserted;
  }

  public void setTotalInserted(Long totalInserted) {
    this.totalInserted = totalInserted;
  }

  public Long getTotalUpdated() {
    return totalUpdated;
  }

  public void setTotalUpdated(Long totalUpdated) {
    this.totalUpdated = totalUpdated;
  }
}
