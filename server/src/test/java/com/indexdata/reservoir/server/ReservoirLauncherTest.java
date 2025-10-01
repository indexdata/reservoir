package com.indexdata.reservoir.server;

import static org.junit.Assert.assertEquals;

import org.junit.jupiter.api.Test;

public class ReservoirLauncherTest {

  @Test
  public void testMain() throws Exception {
    String[] args = {};
    assertEquals(15, ReservoirLauncher.mainNoExit(args));
  }
}
