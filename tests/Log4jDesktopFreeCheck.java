import com.indexdata.reservoir.logging.PropertyChangeEvent;
import com.indexdata.reservoir.logging.PropertyChangeListener;
import io.vertx.core.Vertx;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import org.apache.logging.log4j.core.jmx.LoggerContextAdmin;
import org.folio.okapi.common.logging.FolioLoggingContext;

/** Runs against the packaged JAR in a JVM where java.desktop cannot be resolved. */
public class Log4jDesktopFreeCheck {
  public static void main(String[] args) throws Exception {
    require(ModuleLayer.boot().findModule("java.desktop").isEmpty(), "java.desktop is present");
    checkLog4jClasses();
    var output = new ByteArrayOutputStream();
    var originalOut = System.out;
    try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8);
         var context = new LoggerContext("desktop-free-check")) {
      System.setOut(capture);
      var changes = new ArrayList<PropertyChangeEvent>();
      PropertyChangeListener listener = changes::add;
      context.addPropertyChangeListener(listener);
      var admin = new LoggerContextAdmin(context, Runnable::run);
      var notifications = new AtomicInteger();
      admin.addNotificationListener((notification, handback) -> notifications.incrementAndGet(),
          null, null);

      var first = configuration(context, "first");
      context.start(first);
      context.getLogger("probe").error("before reload");
      int initialChanges = changes.size();
      require(initialChanges > 0, "initial configuration did not notify listeners");
      var second = configuration(context, "second");
      context.setConfiguration(second);
      context.getLogger("probe").error("after reload");

      require(changes.size() > initialChanges, "configuration reload did not notify listeners");
      var change = changes.stream()
          .filter(event -> event.getOldValue() == first && event.getNewValue() == second)
          .findFirst().orElseThrow(() -> new AssertionError("missing configuration replacement event"));
      require(change.getSource() == context, "wrong event source");
      require("config".equals(change.getPropertyName()), "wrong property name");
      require(change.getOldValue() == first && change.getNewValue() == second, "wrong old/new values");
      change.setPropagationId("probe");
      require("probe".equals(change.getPropagationId()), "propagation ID was not retained");
      require(notifications.get() == changes.size(), "JMX listener did not receive all changes");

      int changesBeforeRemoval = changes.size();
      context.removePropertyChangeListener(listener);
      context.updateLoggers();
      require(changes.size() == changesBeforeRemoval, "removed listener was still invoked");
      String logs = output.toString(StandardCharsets.UTF_8);
      require(logs.contains("first before reload"), "initial configuration did not log");
      require(logs.contains("second after reload"), "reloaded configuration did not log");
      checkPackagedConfigurations(context, output);
    } finally {
      System.setOut(originalOut);
    }
    System.out.println("Log4j logging, reload, JMX and FOLIO configurations work without java.desktop");
  }

  private static void checkLog4jClasses() throws Exception {
    var location = LoggerContext.class.getProtectionDomain().getCodeSource().getLocation();
    try (var jar = new JarFile(Path.of(location.toURI()).toFile())) {
      for (var entry : jar.stream().toList()) {
        if (entry.getName().startsWith("org/apache/logging/log4j/core/")
            && entry.getName().endsWith(".class")) {
          try (var input = jar.getInputStream(entry)) {
            String constants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            require(!constants.contains("java/beans/") && !constants.contains("java.beans."),
                "Unpatched java.beans reference in " + entry.getName()
                    + "; review the relocation when upgrading Log4j");
          }
        }
      }
    }
  }

  private static void checkPackagedConfigurations(LoggerContext context, ByteArrayOutputStream output)
      throws Exception {
    var vertx = Vertx.vertx();
    try {
      for (String resource : new String[] {"log4j2.xml", "log4j2-json.xml"}) {
        var location = Log4jDesktopFreeCheck.class.getClassLoader().getResource(resource);
        require(location != null, "missing logging configuration: " + resource);
        try (var input = location.openStream()) {
          context.setConfiguration(new XmlConfiguration(context, new ConfigurationSource(input)));
        }
        output.reset();
        var logged = new CompletableFuture<Void>();
        vertx.runOnContext(unused -> {
          try {
            FolioLoggingContext.put("tenantId", "desktop-free-tenant");
            context.getLogger("probe").error("packaged logging probe");
            logged.complete(null);
          } catch (Throwable error) {
            logged.completeExceptionally(error);
          }
        });
        logged.get(10, TimeUnit.SECONDS);
        String logs = output.toString(StandardCharsets.UTF_8);
        require(logs.contains("packaged logging probe"), resource + " did not log");
        if (resource.equals("log4j2.xml")) {
          require(logs.contains("[desktop-free-tenant]"), "FOLIO tenant lookup did not resolve");
        } else {
          require(logs.contains("\"message\":\"packaged logging probe\""),
              "JSON layout did not encode the event");
        }
      }
    } finally {
      vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
  }

  private static XmlConfiguration configuration(LoggerContext context, String name) throws Exception {
    String xml = """
        <Configuration name="%s" status="ERROR">
          <Appenders>
            <Console name="stdout"><PatternLayout pattern="%s %%m%%n"/></Console>
          </Appenders>
          <Loggers><Root level="error"><AppenderRef ref="stdout"/></Root></Loggers>
        </Configuration>
        """.formatted(name, name);
    return new XmlConfiguration(context,
        new ConfigurationSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
