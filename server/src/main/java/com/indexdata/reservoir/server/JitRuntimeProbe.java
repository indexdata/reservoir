package com.indexdata.reservoir.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.graalvm.polyglot.Context;

/** Check packaged JIT runtime capabilities when invoked by the Docker smoke test. */
public final class JitRuntimeProbe {
  private JitRuntimeProbe() { }

  /** Run the capability checks, optionally resolving the hostname supplied in args. */
  public static void main(String[] args) throws Exception {
    if (!"reservoir".equals(System.getProperty("user.name"))) {
      throw new AssertionError("Non-root user lookup failed");
    }
    Path home = Files.createTempFile(
        Path.of(System.getProperty("user.home")), "probe-", ".tmp");
    Files.delete(home);
    Path temporary = Files.createTempFile("probe-", ".tmp");
    Files.delete(temporary);
    System.out.println("PASS non-root home and temporary files");

    if (!InetAddress.getByName("localhost").isLoopbackAddress()) {
      throw new AssertionError("Host lookup failed");
    }
    // Compose supplies a service hostname to exercise DNS on the test network.
    if (args.length > 0) {
      System.out.println("PASS DNS lookup: " + InetAddress.getByName(args[0]));
    }
    var zoneRules = ZoneId.of("Europe/Copenhagen").getRules();
    if (!zoneRules.getOffset(Instant.parse("2026-01-15T12:00:00Z"))
            .equals(ZoneOffset.ofHours(1))
        || !zoneRules.getOffset(Instant.parse("2026-07-15T12:00:00Z"))
            .equals(ZoneOffset.ofHours(2))) {
      throw new AssertionError("Timezone rules must provide winter and summer offsets");
    }
    System.out.println("PASS host lookup and timezone rules; default zone: "
        + ZoneId.systemDefault());

    TrustManagerFactory defaults = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm());
    defaults.init((KeyStore) null);
    if (((X509TrustManager) defaults.getTrustManagers()[0]).getAcceptedIssuers().length == 0) {
      throw new AssertionError("Default CA trust store is empty");
    }
    if (!OpenSsl.isAvailable()) {
      throw new AssertionError("Native TLS is unavailable", OpenSsl.unavailabilityCause());
    }
    if (!Epoll.isAvailable()) {
      throw new AssertionError("Native epoll is unavailable", Epoll.unavailabilityCause());
    }
    EpollSocketChannel epoll = new EpollSocketChannel();
    epoll.unsafe().closeForcibly();
    System.out.println("PASS native epoll and " + OpenSsl.versionString());

    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(Path.of("/probes/tls.p12"))) {
      store.load(input, "changeit".toCharArray());
    }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, "changeit".toCharArray());
    TrustManagerFactory trust = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    SSLContext jdkTls = SSLContext.getInstance("TLS");
    jdkTls.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
    checkTls(jdkTls, trust, false);
    checkTls(jdkTls, trust, true);
    System.out.println("PASS JDK and native Netty TLS handshakes; default CA store present");

    try (Context js = Context.newBuilder("js")
        .allowExperimentalOptions(true)
        .option("engine.CompileImmediately", "true")
        .option("engine.BackgroundCompilation", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.TraceCompilation", "true")
        .build()) {
      var sum = js.eval("js",
          "(n => { let s = 0; for (let i = 0; i < n; i++) s += i; return s; })");
      for (int i = 0; i < 10; i++) {
        if (sum.execute(1000).asInt() != 499500) {
          throw new AssertionError("JavaScript result mismatch");
        }
      }
      System.out.println("PASS JavaScript with synchronous compilation: "
          + js.getEngine().getImplementationName());
    }
  }

  private static void checkTls(SSLContext context, TrustManagerFactory trust, boolean nativeClient)
      throws Exception {
    try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
        .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      server.setSoTimeout(15000);
      CompletableFuture<Void> accepted = CompletableFuture.runAsync(() -> {
        try (SSLSocket peer = (SSLSocket) server.accept()) {
          peer.setSoTimeout(15000);
          peer.startHandshake();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      if (nativeClient) {
        SslContext tls = SslContextBuilder.forClient().sslProvider(SslProvider.OPENSSL)
            .trustManager(trust).build();
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
          var channel = new Bootstrap().group(group).channel(NioSocketChannel.class)
              .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel channel) {
                  channel.pipeline().addLast(
                      tls.newHandler(channel.alloc(), "localhost", server.getLocalPort()));
                }
              }).connect("127.0.0.1", server.getLocalPort()).sync().channel();
          try {
            channel.pipeline().get(SslHandler.class).handshakeFuture().sync();
          } finally {
            channel.close().sync();
          }
        } finally {
          group.shutdownGracefully().sync();
          ReferenceCountUtil.release(tls);
        }
      } else {
        try (SSLSocket client = (SSLSocket) context.getSocketFactory()
            .createSocket("127.0.0.1", server.getLocalPort())) {
          client.setSoTimeout(15000);
          client.startHandshake();
        }
      }
      accepted.get(20, TimeUnit.SECONDS);
    }
  }
}
