package com.indexdata.reservoir.server.substitutes;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * GraalVM native image substitutions for Netty's CleanerJava25 class.
 *
 * <p>This class replaces the original implementation of CleanerJava25 in Netty
 * to disable use of cleaner by letting isSupported() return false.
 * GraalVM does not support ofShared with runtime compilations at least as of version 25.0.0.
 * https://github.com/netty/netty/issues/15762
 * https://github.com/oracle/graal/pull/11788
 */
public final class NettyCleanerSubstitutes {

  private NettyCleanerSubstitutes() {
      // Prevent instantiation
  }

  @TargetClass(className = "io.netty.util.internal.CleanerJava25")
  public static final class CleanerJava25Substitute {
    /**
     * Substitute for the isSupported method in CleanerJava25.
     * Making it return false forces Netty to use fallback mechanisms
     * instead of using Arena.ofShared().
     *
     * @return false to indicate that Java 25 Cleaner is not supported
     */
    @Substitute
    public static boolean isSupported() {
      return false;
    }
  }
}
