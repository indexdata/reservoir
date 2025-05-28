package com.indexdata.reservoir.server;

import org.folio.okapi.testing.UtilityClassTester;
import org.junit.Assert;
import org.junit.Test;

import com.indexdata.reservoir.server.JavaScriptCheck;

public class JavaScriptCheckTest {

  @Test
  public void isUtilityClass() {
    UtilityClassTester.assertUtilityClass(JavaScriptCheck.class);
  }

  @Test
  public void testOK() {
    JavaScriptCheck.check();
  }

  @Test
  public void test2() {
    Assert.assertThrows(IllegalStateException.class, () -> JavaScriptCheck.check("x => 2"));
  }

  @Test
  public void testString() {
    Assert.assertThrows(IllegalStateException.class, () -> JavaScriptCheck.check("x => 'a'"));
  }

  @Test
  public void testSyntax() {
    Assert.assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> JavaScriptCheck.check("x => '"));
  }

}
