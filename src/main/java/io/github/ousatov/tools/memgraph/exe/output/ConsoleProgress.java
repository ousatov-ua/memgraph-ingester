package io.github.ousatov.tools.memgraph.exe.output;

import io.github.ousatov.tools.memgraph.def.Const;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Renders finite and indeterminate terminal progress with ASCII animation.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings("java:S106")
public final class ConsoleProgress implements AutoCloseable {

  private static final int LABEL_WIDTH = 50;
  private static final int BAR_WIDTH = 29;
  private static final int INDETERMINATE_WIDTH = BAR_WIDTH;
  private static final int MARKER_WIDTH = 3;
  private static final String TRUNCATION = "...";
  private static final String[] SPINNER = {"-", "\\", "|", "/"};

  private final PrintStream out;
  private final String label;
  private final Integer total;
  private final boolean interactive;
  private final boolean colors;
  private final ConsoleStatusLine.StatusSession statusSession;
  private final ScheduledExecutorService executor;
  private final ScheduledFuture<?> animation;

  private int frame;
  private boolean closed;

  /** Opens a finite progress indicator on the process error stream. */
  public static ConsoleProgress finite(String label, int total) {
    return finite(label, total, System.err, ConsoleStatusLine.isInteractive());
  }

  /** Opens a finite progress indicator on the given stream. */
  public static ConsoleProgress finite(
      String label, int total, PrintStream out, boolean interactive) {
    return finite(label, total, out, interactive, true);
  }

  /** Opens a finite progress indicator on the given stream. */
  public static ConsoleProgress finite(
      String label, int total, PrintStream out, boolean interactive, boolean renderInitial) {
    if (total < 0) {
      throw new IllegalArgumentException("total must be >= 0");
    }
    return new ConsoleProgress(label, total, out, interactive, Duration.ZERO, false, renderInitial);
  }

  /** Opens an indeterminate progress indicator on the process error stream. */
  public static ConsoleProgress indeterminate(String label) {
    return indeterminate(
        label, System.err, ConsoleStatusLine.isInteractive(), Duration.ofMillis(120), true);
  }

  /** Opens an indeterminate progress indicator on the given stream. */
  public static ConsoleProgress indeterminate(
      String label, PrintStream out, boolean interactive, Duration interval, boolean animated) {
    return indeterminate(label, out, interactive, interval, animated, true);
  }

  /** Opens an indeterminate progress indicator on the given stream. */
  public static ConsoleProgress indeterminate(
      String label,
      PrintStream out,
      boolean interactive,
      Duration interval,
      boolean animated,
      boolean renderInitial) {
    return new ConsoleProgress(label, null, out, interactive, interval, animated, renderInitial);
  }

  private ConsoleProgress(
      String label,
      Integer total,
      PrintStream out,
      boolean interactive,
      Duration interval,
      boolean animated,
      boolean renderInitial) {
    this.out = Objects.requireNonNull(out, Const.Params.OUT);
    this.label = Objects.requireNonNull(label, "label");
    this.total = total;
    this.interactive = interactive;
    this.colors = AnsiStyle.colorsEnabled(interactive);
    this.statusSession = interactive ? openStatusSessionFor(total == null, this.out) : null;
    Objects.requireNonNull(interval, "interval");
    if (renderInitial) {
      render(0);
    }
    if (total == null && interactive && animated) {
      this.executor =
          Executors.newSingleThreadScheduledExecutor(
              task -> {
                Thread thread = new Thread(task, "memgraph-console-progress");
                thread.setDaemon(true);
                return thread;
              });
      this.animation =
          executor.scheduleAtFixedRate(
              this::renderNextFrame,
              Math.max(1L, interval.toMillis()),
              Math.max(1L, interval.toMillis()),
              TimeUnit.MILLISECONDS);
    } else {
      this.executor = null;
      this.animation = null;
    }
  }

  private static ConsoleStatusLine.StatusSession openStatusSessionFor(
      boolean exclusive, PrintStream out) {
    return exclusive
        ? ConsoleStatusLine.openExclusiveStatusSession(out)
        : ConsoleStatusLine.openStatusSession(out);
  }

  /** Updates a finite progress indicator. */
  public synchronized void update(int done) {
    update(label, done);
  }

  /** Updates a finite progress indicator using a one-off label. */
  public synchronized void update(String label, int done) {
    if (total == null || closed) {
      return;
    }
    render(label, Math.clamp(done, 0, total));
  }

  /** Stops the animation and leaves {@code text} on the active status row. */
  public synchronized void complete(String text) {
    Objects.requireNonNull(text, Const.Params.TEXT);
    if (closed) {
      return;
    }
    stopAnimation();
    closed = true;
    if (interactive) {
      ConsoleStatusLine.update(out, text);
    } else {
      ConsoleStatusLine.line(out, text);
    }
    if (statusSession != null) {
      statusSession.close();
    }
  }

  /** Stops the animation and leaves a completed progress row with {@code label}. */
  public synchronized void completeLabel(String label) {
    complete(renderComplete(label, colors));
  }

  /** Stops the animation and clears its active status row without printing a new line. */
  public synchronized void discard() {
    if (closed) {
      return;
    }
    stopAnimation();
    closed = true;
    if (interactive) {
      ConsoleStatusLine.clear(out);
    }
    if (statusSession != null) {
      statusSession.close();
    }
  }

  private synchronized void renderNextFrame() {
    if (!closed) {
      frame++;
      render(label, 0);
    }
  }

  private void render(int done) {
    render(label, done);
  }

  private void render(String label, int done) {
    String text =
        total == null
            ? renderIndeterminate(label, frame, colors)
            : renderFinite(label, done, total, frame, colors);
    if (interactive) {
      ConsoleStatusLine.update(out, text);
    } else {
      ConsoleStatusLine.line(out, text);
    }
  }

  static String renderFinite(String label, int done, int total, int frame, boolean colors) {
    int percent = total == 0 ? 100 : (int) Math.round(done * 100.0 / total);
    int filled = total == 0 ? BAR_WIDTH : Math.clamp(done * BAR_WIDTH / total, 0, BAR_WIDTH);
    String bar =
        filled >= BAR_WIDTH
            ? "=".repeat(BAR_WIDTH)
            : "=".repeat(filled)
                + ">"
                + Const.Symbols.SPACE.repeat(Math.max(0, BAR_WIDTH - filled - 1));
    String spinner = AnsiStyle.spinner(SPINNER[Math.floorMod(frame, SPINNER.length)], colors);
    return spinner
        + Const.Symbols.SPACE
        + AnsiStyle.bold(formatLabel(label), colors)
        + Const.Symbols.SPACE
        + AnsiStyle.progress("[" + bar + "]", colors)
        + Const.Symbols.SPACE
        + percent
        + "%"
        + Const.Symbols.SPACE
        + done
        + Const.Symbols.SLASH
        + total;
  }

  static String renderIndeterminate(String label, int frame, boolean colors) {
    int maxOffset = INDETERMINATE_WIDTH - MARKER_WIDTH;
    int cycle = maxOffset * 2;
    int offset = Math.floorMod(frame, cycle);
    boolean forward = offset <= maxOffset;
    int position = forward ? offset : cycle - offset;
    String marker = forward ? "==>" : "<==";
    String track =
        Const.Symbols.SPACE.repeat(position)
            + marker
            + Const.Symbols.SPACE.repeat(INDETERMINATE_WIDTH - position - MARKER_WIDTH);
    return AnsiStyle.spinner(SPINNER[Math.floorMod(frame, SPINNER.length)], colors)
        + Const.Symbols.SPACE
        + AnsiStyle.bold(formatLabel(label), colors)
        + Const.Symbols.SPACE
        + AnsiStyle.progress("[" + track + "]", colors);
  }

  static String renderComplete(String label, boolean colors) {
    return AnsiStyle.spinner(SPINNER[0], colors)
        + Const.Symbols.SPACE
        + AnsiStyle.bold(formatLabel(label), colors)
        + Const.Symbols.SPACE
        + AnsiStyle.progress("[" + "=".repeat(BAR_WIDTH) + "]", colors);
  }

  private static String formatLabel(String label) {
    if (label.length() > LABEL_WIDTH) {
      return label.substring(0, LABEL_WIDTH - TRUNCATION.length()) + TRUNCATION;
    }
    int padding = Math.max(0, LABEL_WIDTH - label.length());
    return label + Const.Symbols.SPACE.repeat(padding);
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    stopAnimation();
    if (interactive) {
      ConsoleStatusLine.finish(out);
      statusSession.close();
    }
  }

  private void stopAnimation() {
    if (animation != null) {
      animation.cancel(true);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }
}
