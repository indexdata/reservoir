package com.indexdata.reservoir.server.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable domain representation of a cluster and its latest-per-source records. */
public record Cluster(
    UUID clusterId,
    LocalDateTime datestamp,
    List<GlobalRecord> records,
    List<String> matchValues
) {

  private static final Comparator<GlobalRecord> RECORD_ORDER =
      Comparator.comparing(GlobalRecord::sourceId)
          .thenComparingInt(GlobalRecord::sourceVersion)
          .thenComparing(GlobalRecord::localId);

  public Cluster {
    Objects.requireNonNull(clusterId, "clusterId must not be null");
    records = records == null ? List.of() : List.copyOf(records);
    matchValues = matchValues == null ? null : List.copyOf(matchValues);
  }

  /**
   * Build a normalized cluster keeping only the highest {@code sourceVersion} per {@code sourceId}
   * and sorting records deterministically by {@code (sourceId, sourceVersion, localId)}.
   */
  public static Cluster of(UUID clusterId, LocalDateTime datestamp,
      List<GlobalRecord> rawRecords, List<String> matchValues) {
    return new Cluster(clusterId, datestamp, normalizeRecords(rawRecords), matchValues);
  }

  /** Keep only the latest sourceVersion per sourceId and sort deterministically. */
  public static List<GlobalRecord> normalizeRecords(List<GlobalRecord> rawRecords) {
    if (rawRecords == null || rawRecords.isEmpty()) {
      return List.of();
    }
    Map<String, Integer> latestVersionBySource = new HashMap<>();
    for (GlobalRecord rec : rawRecords) {
      latestVersionBySource.merge(rec.sourceId(), rec.sourceVersion(), Math::max);
    }
    List<GlobalRecord> filtered = new ArrayList<>(rawRecords.size());
    for (GlobalRecord rec : rawRecords) {
      if (latestVersionBySource.get(rec.sourceId()) == rec.sourceVersion()) {
        filtered.add(rec);
      }
    }
    filtered.sort(RECORD_ORDER);
    return filtered;
  }
}
