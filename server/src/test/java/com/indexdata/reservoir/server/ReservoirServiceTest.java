package com.indexdata.reservoir.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReservoirServiceTest {

  @Test
  void detectsDeprecatedCqlIndex() {
    assertTrue(ReservoirService.usesCqlIndex(
        "matchkeyId=isbn AND sourceId=source", "matchkeyId"));
    assertTrue(ReservoirService.usesCqlIndex(
        "sourceId=source sortby matchkeyId", "matchkeyId"));
    assertTrue(ReservoirService.usesCqlIndex(
        "poolId=isbn AND matchkeyId=legacy", "matchkeyId"));
    assertTrue(ReservoirService.usesCqlIndex(
        "MATCHKEYID=isbn", "matchkeyId"));
  }

  @Test
  void ignoresCqlTermContainingDeprecatedIndexName() {
    assertFalse(ReservoirService.usesCqlIndex(
        "poolId=\"matchkeyId=isbn\"", "matchkeyId"));
    assertFalse(ReservoirService.usesCqlIndex(
        "poolId=isbn AND sourceId=source", "matchkeyId"));
    assertFalse(ReservoirService.usesCqlIndex(null, "matchkeyId"));
  }

  @Test
  void sanitizesUserAgentForLogging() {
    assertEquals("-", ReservoirService.sanitizeUserAgent(null));
    assertEquals("-", ReservoirService.sanitizeUserAgent("  "));
    assertEquals("client injected", ReservoirService.sanitizeUserAgent("client\ninjected"));
    assertEquals(256, ReservoirService.sanitizeUserAgent("x".repeat(300)).length());
  }
}
