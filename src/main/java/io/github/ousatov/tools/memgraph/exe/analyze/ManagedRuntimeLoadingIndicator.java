package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleProgress;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Objects;

/**
 * Prints CLI feedback while a managed runtime is being prepared.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings("java:S106")
final class ManagedRuntimeLoadingIndicator implements AutoCloseable {

  private static final Duration DEFAULT_INTERVAL =
      AppConfig.durationValue("runtime.managed.loading-interval");

  private final PrintStream out;
  private final String runtimeName;
  private final ConsoleProgress progress;

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
    this.out = Objects.requireNonNull(out, Const.Params.OUT);
    this.runtimeName = Objects.requireNonNull(runtimeName, "runtimeName");
    Objects.requireNonNull(interval, "interval");
    if (interactive && animated) {
      this.progress =
          ConsoleProgress.indeterminate(
              "Loading managed runtime: " + this.runtimeName,
              this.out,
              true,
              interval,
              animated,
              true);
    } else {
      this.progress = null;
      writeLine("Loading managed runtime: " + this.runtimeName + "...");
    }
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
    if (progress != null) {
      progress.completeLabel(finalProgressLabel());
      ConsoleStatusLine.finish(out);
      return;
    }
    writeLine(finalText());
  }

  private String finalProgressLabel() {
    if (succeeded) {
      return "Loaded managed runtime: " + runtimeName;
    }
    return "Failed to load managed runtime: " + runtimeName;
  }

  private String finalText() {
    if (succeeded) {
      return "Loaded managed runtime: " + runtimeName + Const.Symbols.DOT;
    }
    return "Failed to load managed runtime: " + runtimeName + Const.Symbols.DOT;
  }

  private synchronized void writeLine(String text) {
    ConsoleStatusLine.line(out, text);
  }
}
