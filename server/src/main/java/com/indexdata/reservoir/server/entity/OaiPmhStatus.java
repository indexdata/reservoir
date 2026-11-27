package com.indexdata.reservoir.server.entity;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Pure, immutable domain record for OAI-PMH client harvest status.
 * Uses explicit codecs in {@link OaiPmhStatusJson} rather than Jackson reflection.
 */
public record OaiPmhStatus(
    OaiPmhJobStatus status,
    String error,
    LocalDateTime lastActiveTimestamp,
    LocalDateTime lastStartedTimestamp,
    Long lastRunningTimeMs,
    Long lastRecsPerSec,
    Long lastTotalRecords,
    Long totalDeleted,
    Long totalInserted,
    Long totalUpdated,
    Long totalRecords,
    Integer totalRequests
) {

  /** Initial idle status with zeroed counters for a newly created OAI-PMH client. */
  public static OaiPmhStatus initialIdle() {
    return new OaiPmhStatus(
        OaiPmhJobStatus.IDLE, null, null, null, null, null, null,
        0L, 0L, 0L, 0L, 0);
  }

  /** Status with all fields null, useful as a blank starting point in tests. */
  public static OaiPmhStatus empty() {
    return new OaiPmhStatus(
        null, null, null, null, null, null, null,
        null, null, null, null, null);
  }

  public boolean isRunning() {
    return status == OaiPmhJobStatus.RUNNING;
  }

  /** Return a copy with an updated job status. */
  public OaiPmhStatus withStatus(OaiPmhJobStatus newStatus) {
    return new OaiPmhStatus(
        newStatus, error, lastActiveTimestamp, lastStartedTimestamp,
        lastRunningTimeMs, lastRecsPerSec, lastTotalRecords,
        totalDeleted, totalInserted, totalUpdated, totalRecords, totalRequests);
  }

  /** Return a copy with an updated error message. */
  public OaiPmhStatus withError(String newError) {
    return new OaiPmhStatus(
        status, newError, lastActiveTimestamp, lastStartedTimestamp,
        lastRunningTimeMs, lastRecsPerSec, lastTotalRecords,
        totalDeleted, totalInserted, totalUpdated, totalRecords, totalRequests);
  }

  /** Return a copy with an updated last active timestamp. */
  public OaiPmhStatus withLastActiveTimestamp(LocalDateTime timestamp) {
    return new OaiPmhStatus(
        status, error, timestamp, lastStartedTimestamp,
        lastRunningTimeMs, lastRecsPerSec, lastTotalRecords,
        totalDeleted, totalInserted, totalUpdated, totalRecords, totalRequests);
  }

  /** Return a copy with an updated last started timestamp. */
  public OaiPmhStatus withLastStartedTimestamp(LocalDateTime timestamp) {
    return new OaiPmhStatus(
        status, error, lastActiveTimestamp, timestamp,
        lastRunningTimeMs, lastRecsPerSec, lastTotalRecords,
        totalDeleted, totalInserted, totalUpdated, totalRecords, totalRequests);
  }

  /** Return a copy with an updated running duration in milliseconds. */
  public OaiPmhStatus withLastRunningTimeMs(Long runningTimeMs) {
    return new OaiPmhStatus(
        status, error, lastActiveTimestamp, lastStartedTimestamp,
        runningTimeMs, lastRecsPerSec, lastTotalRecords,
        totalDeleted, totalInserted, totalUpdated, totalRecords, totalRequests);
  }

  /** Return a copy with an updated cumulative record count. */
  public OaiPmhStatus withTotalRecords(Long newTotalRecords) {
    return new OaiPmhStatus(
        status, error, lastActiveTimestamp, lastStartedTimestamp,
        lastRunningTimeMs, lastRecsPerSec, lastTotalRecords,
        totalDeleted, totalInserted, totalUpdated, newTotalRecords, totalRequests);
  }

  /** Return a copy with an updated last-run record count. */
  public OaiPmhStatus withLastTotalRecords(Long newLastTotalRecords) {
    return new OaiPmhStatus(
        status, error, lastActiveTimestamp, lastStartedTimestamp,
        lastRunningTimeMs, lastRecsPerSec, newLastTotalRecords,
        totalDeleted, totalInserted, totalUpdated, totalRecords, totalRequests);
  }

  /** Recalculate lastRecsPerSec from lastTotalRecords and lastRunningTimeMs. */
  public OaiPmhStatus withCalculatedLastRecsPerSec() {
    Long rate = lastRunningTimeMs != null && lastRunningTimeMs > 0L
        ? lastTotalRecords * 1000L / lastRunningTimeMs
        : null;
    return new OaiPmhStatus(
        status, error, lastActiveTimestamp, lastStartedTimestamp,
        lastRunningTimeMs, rate, lastTotalRecords,
        totalDeleted, totalInserted, totalUpdated, totalRecords, totalRequests);
  }

  /** Transition status into the running state at the given UTC timestamp. */
  public OaiPmhStatus startingAt(LocalDateTime now) {
    if (isRunning()) {
      return new OaiPmhStatus(
          OaiPmhJobStatus.RUNNING, null, lastActiveTimestamp, lastStartedTimestamp,
          lastRunningTimeMs, lastRecsPerSec, lastTotalRecords,
          totalDeleted, totalInserted, totalUpdated, totalRecords, totalRequests);
    }
    return new OaiPmhStatus(
        OaiPmhJobStatus.RUNNING, null, lastActiveTimestamp, now,
        lastRunningTimeMs, null, 0L,
        totalDeleted, totalInserted, totalUpdated, totalRecords, totalRequests);
  }

  /** Transition status into the idle state at the given UTC timestamp. */
  public OaiPmhStatus stoppedAt(LocalDateTime now) {
    Long runningMs = lastStartedTimestamp != null
        ? Duration.between(lastStartedTimestamp, now).toMillis()
        : lastRunningTimeMs;
    return new OaiPmhStatus(
        OaiPmhJobStatus.IDLE, error, now, lastStartedTimestamp,
        runningMs, lastRecsPerSec, lastTotalRecords,
        totalDeleted, totalInserted, totalUpdated, totalRecords, totalRequests);
  }

  /** Return a copy with totalRequests incremented by one. */
  public OaiPmhStatus incrementingRequests() {
    int next = (totalRequests != null ? totalRequests : 0) + 1;
    return new OaiPmhStatus(
        status, error, lastActiveTimestamp, lastStartedTimestamp,
        lastRunningTimeMs, lastRecsPerSec, lastTotalRecords,
        totalDeleted, totalInserted, totalUpdated, totalRecords, next);
  }

  /** Update ingest counters after an insert (true), update (false), or delete (null). */
  public OaiPmhStatus recordingIngest(Boolean inserted) {
    long nextTotal = (totalRecords != null ? totalRecords : 0L) + 1L;
    long nextLastTotal = (lastTotalRecords != null ? lastTotalRecords : 0L) + 1L;
    long nextDeleted = totalDeleted != null ? totalDeleted : 0L;
    long nextInserted = totalInserted != null ? totalInserted : 0L;
    long nextUpdated = totalUpdated != null ? totalUpdated : 0L;
    if (inserted == null) {
      nextDeleted++;
    } else if (inserted) {
      nextInserted++;
    } else {
      nextUpdated++;
    }
    return new OaiPmhStatus(
        status, error, lastActiveTimestamp, lastStartedTimestamp,
        lastRunningTimeMs, lastRecsPerSec, nextLastTotal,
        nextDeleted, nextInserted, nextUpdated, nextTotal, totalRequests);
  }

  /** Update elapsed running time and throughput rate at the end of an OAI page. */
  public OaiPmhStatus completingPage() {
    long runningTimeMs = Duration.between(lastStartedTimestamp, lastActiveTimestamp).toMillis();
    return withLastRunningTimeMs(runningTimeMs).withCalculatedLastRecsPerSec();
  }
}
