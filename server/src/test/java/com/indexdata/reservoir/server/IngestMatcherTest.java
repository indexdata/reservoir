package com.indexdata.reservoir.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import com.indexdata.reservoir.module.Module;
import com.indexdata.reservoir.module.ModuleExecutable;
import com.indexdata.reservoir.module.ModuleInvocation;
import com.indexdata.reservoir.server.entity.CodeModuleEntity;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class IngestMatcherTest {
  private Vertx vertx;

  @Before
  public void setUp() {
    vertx = Vertx.vertx();
  }

  @After
  public void tearDown(TestContext context) {
    vertx.close().onComplete(context.asyncAssertSuccess());
  }

  @Test
  public void closeIsIdempotent(TestContext context) {
    AtomicInteger terminated = new AtomicInteger();
    ModuleExecutable executable = new ModuleExecutable(
        () -> new TestModule(terminated), new ModuleInvocation("test"), vertx, 2);
    IngestMatcher matcher = new IngestMatcher();
    matcher.moduleExecutables = new ModuleExecutable[] { executable };

    Future<Void> firstClose = matcher.close();
    Future<Void> secondClose = matcher.close();

    assertThat(secondClose, sameInstance(firstClose));
    firstClose.onComplete(context.asyncAssertSuccess(ignored ->
        assertThat(terminated.get(), is(2))));
  }

  private static final class TestModule implements Module {
    private final AtomicInteger terminated;

    private TestModule(AtomicInteger terminated) {
      this.terminated = terminated;
    }

    @Override
    public void initialize(CodeModuleEntity entity) {
    }

    @Override
    public JsonObject execute(String symbol, JsonObject input) {
      return input;
    }

    @Override
    public Collection<String> executeAsCollection(String symbol, JsonObject input) {
      return List.of();
    }

    @Override
    public void terminate() {
      terminated.incrementAndGet();
    }
  }
}
