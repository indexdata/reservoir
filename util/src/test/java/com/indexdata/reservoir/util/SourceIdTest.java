package org.folio.reservoir.util;

import org.junit.Assert;
import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SourceIdTest {

  @Test
  public void good() {
    assertThat(new SourceId("A").toString(), is("A"));
    assertThat(new SourceId("a").toString(), is("A"));
    assertThat(new SourceId("/-:2a").toString(), is("/-:2A"));
    assertThat(new SourceId("0123456789012345").toString(), is("0123456789012345"));
  }

  @Test
  public void bad() {
    Assert.assertThrows(IllegalArgumentException.class, () -> new SourceId("_"));
    Assert.assertThrows(IllegalArgumentException.class, () -> new SourceId("_a_"));
    Assert.assertThrows(IllegalArgumentException.class, () -> new SourceId("01234567890123456"));
    Assert.assertThrows(IllegalArgumentException.class, () -> new SourceId(" "));
  }
}
