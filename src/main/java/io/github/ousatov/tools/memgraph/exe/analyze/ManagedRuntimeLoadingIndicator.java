package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Objects;

/**
 * Prints CLI feedback while a managed runtime is being prepared.
 *
 * @author Oleksii Usatov
 */
final class ManagedRuntimeLoadingIndicator implements AutoCloseable {

  private static final Duration DEFAULT_INTERVAL = Duration.ofMillis(500);

  private final PrintStream out;
  private final String runtimeName;

  private boolean succeeded;
  private boolean closed;

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

  @SuppressWarnings("java:S1172")
  static ManagedRuntimeLoadingIndicator start(
      String runtimeName,
      PrintStream out,
      Duration interval,
      boolean interactive,
      boolean animated) {
    return new ManagedRuntimeLoadingIndicator(runtimeName, out, interval);
  }

  private ManagedRuntimeLoadingIndicator(String runtimeName, PrintStream out, Duration interval) {
    this.out = Objects.requireNonNull(out, "out");
    this.runtimeName = Objects.requireNonNull(runtimeName, "runtimeName");
    Objects.requireNonNull(interval, "interval");
    writeLine("Loading managed runtime: " + this.runtimeName + "...");
  }

  void succeeded() {
    succeeded = true;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    writeFinal();
  }

  private void writeFinal() {
    if (succeeded) {
      writeLine("Loaded managed runtime: " + runtimeName + ".");
    } else {
      writeLine("Failed to load managed runtime: " + runtimeName + ".");
    }
  }

  private synchronized void writeLine(String text) {
    ConsoleStatusLine.line(out, text);
  }
}
