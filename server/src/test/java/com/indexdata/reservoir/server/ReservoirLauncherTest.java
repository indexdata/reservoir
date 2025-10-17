package com.indexdata.reservoir.server;

import static org.junit.Assert.assertEquals;

import org.junit.jupiter.api.Test;

public class ReservoirLauncherTest {

  @Test
  public void testMainFail() throws Exception {
    String[] args = {"-conf", "\"port\":9230}"};
    assertEquals(15, ReservoirLauncher.launch(args));
  }
}
