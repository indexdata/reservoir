package com.indexdata.reservoir.util;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class AsyncCodecTest {
  Vertx vertx;

  @Before
  public void before() {
    vertx = Vertx.vertx();
  }

  @After
  public void after(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void compressAndDecompress(TestContext context){
    String s = "compression test";
    AsyncCodec.compress(vertx, Buffer.buffer(s))
     .compose(bc -> AsyncCodec.decompress(vertx, bc))
     .onComplete(context.asyncAssertSuccess(bd -> context.assertEquals(s, bd.toString())));
  }
}
