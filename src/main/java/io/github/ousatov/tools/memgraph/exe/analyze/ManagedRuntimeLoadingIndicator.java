package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shows lightweight CLI feedback while a managed runtime is being prepared. */
final class ManagedRuntimeLoadingIndicator implements AutoCloseable {

  private static final Duration DEFAULT_INTERVAL = Duration.ofMillis(500);
  private static final int MAX_DOTS = 6;
  private static final int JOIN_TIMEOUT_MILLIS = 100;

  private final PrintStream out;
  private final String message;
  private final Duration interval;
  private final boolean interactive;
  private final boolean animated;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final ConsoleStatusLine.StatusSession statusSession;
  private final Thread thread;

  private volatile boolean succeeded;

  static ManagedRuntimeLoadingIndicator start(String runtimeName) {
    return start(runtimeName, true);
  }

  static ManagedRuntimeLoadingIndicator start(String runtimeName, boolean animated) {
    PrintStream out = System.err;
    return start(runtimeName, out, DEFAULT_INTERVAL, ConsoleStatusLine.isInteractive(), animated);
  }

  static ManagedRuntimeLoadingIndicator steady(String runtimeName) {
    return start(
        runtimeName, System.err, DEFAULT_INTERVAL, ConsoleStatusLine.isInteractive(), false);
  }

  static ManagedRuntimeLoadingIndicator start(
      String runtimeName, PrintStream out, Duration interval) {
    return start(runtimeName, out, interval, false, true);
  }

  static ManagedRuntimeLoadingIndicator start(
      String runtimeName, PrintStream out, Duration interval, boolean interactive) {
    return start(runtimeName, out, interval, interactive, true);
  }

  static ManagedRuntimeLoadingIndicator steady(
      String runtimeName, PrintStream out, Duration interval, boolean interactive) {
    return start(runtimeName, out, interval, interactive, false);
  }

  static ManagedRuntimeLoadingIndicator start(
      String runtimeName,
      PrintStream out,
      Duration interval,
      boolean interactive,
      boolean animated) {
    return new ManagedRuntimeLoadingIndicator(runtimeName, out, interval, interactive, animated);
  }

  private ManagedRuntimeLoadingIndicator(
      String runtimeName,
      PrintStream out,
      Duration interval,
      boolean interactive,
      boolean animated) {
    this.out = Objects.requireNonNull(out, "out");
    this.message = "Loading managed runtime: " + Objects.requireNonNull(runtimeName, "runtimeName");
    this.interval = Objects.requireNonNull(interval, "interval");
    this.interactive = interactive;
    this.animated = animated;
    if (interactive && animated) {
      this.statusSession = ConsoleStatusLine.openExclusiveStatusSession(this.out);
      writeFrame(1);
      this.thread =
          Thread.ofPlatform()
              .daemon()
              .name("memgraph-ingester-managed-runtime-loading")
              .unstarted(this::animate);
      this.thread.start();
    } else {
      this.statusSession = null;
      this.thread = null;
      writeLine(message + "...");
    }
  }

  void succeeded() {
    succeeded = true;
  }

  @Override
  public void close() {
    if (!running.getAndSet(false)) {
      return;
    }
    if (thread != null) {
      thread.interrupt();
      joinThread();
    }
    try {
      writeFinal();
    } finally {
      if (statusSession != null) {
        statusSession.close();
      }
    }
  }

  private void animate() {
    int dots = 1;
    while (running.get()) {
      try {
        Thread.sleep(interval.toMillis());
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        return;
      }
      dots = dots == MAX_DOTS ? 1 : dots + 1;
      writeFrame(dots);
    }
  }

  private void joinThread() {
    try {
      thread.join(JOIN_TIMEOUT_MILLIS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private void writeFrame(int dots) {
    ConsoleStatusLine.update(out, message + ".".repeat(dots));
  }

  private void writeFinal() {
    if (interactive && animated) {
      ConsoleStatusLine.update(out, message + (succeeded ? " done." : " failed."));
      ConsoleStatusLine.finish(out);
    } else {
      writeLine(message + (succeeded ? " done." : " failed."));
    }
    out.flush();
  }

  private synchronized void writeLine(String text) {
    ConsoleStatusLine.line(out, text);
  }
}
