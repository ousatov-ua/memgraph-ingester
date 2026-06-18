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

  // Modern interactive glyphs (used only on a color-capable TTY).
  private static final String[] BRAILLE = {
    "\u280B", "\u2819", "\u2839", "\u2838", "\u283C",
    "\u2834", "\u2826", "\u2827", "\u2807", "\u280F"
  };
  private static final char FULL_BLOCK = '\u2588'; // █
  private static final char EMPTY_BLOCK = '\u2591'; // ░
  private static final char MEDIUM_SHADE = '\u2592'; // ▒
  private static final char DARK_SHADE = '\u2593'; // ▓
  // Symmetric glow (dim edges -> bright core) that travels for indeterminate bars.
  private static final char[] GLOW = {
    EMPTY_BLOCK, MEDIUM_SHADE, DARK_SHADE, FULL_BLOCK, DARK_SHADE, MEDIUM_SHADE, EMPTY_BLOCK
  };
  private static final String CHECK_MARK = "\u2713"; // ✓

  private static final Duration DEFAULT_INTERVAL = Duration.ofMillis(80);

  private final PrintStream out;
  private final String label;
  private final Integer total;
  private final boolean interactive;
  private final boolean colors;
  private final ConsoleStatusLine.StatusSession statusSession;
  private final ScheduledExecutorService executor;
  private final ScheduledFuture<?> animation;

  private String currentLabel;
  private int currentDone;
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
    return new ConsoleProgress(
        label, total, out, interactive, DEFAULT_INTERVAL, true, renderInitial);
  }

  /** Opens an indeterminate progress indicator on the process error stream. */
  public static ConsoleProgress indeterminate(String label) {
    return indeterminate(
        label, System.err, ConsoleStatusLine.isInteractive(), DEFAULT_INTERVAL, true);
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
    this.currentLabel = label;
    Objects.requireNonNull(interval, "interval");
    if (renderInitial) {
      render(0);
    }
    if (interactive && animated) {
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
    currentLabel = label;
    currentDone = Math.clamp(done, 0, total);
    render(currentLabel, currentDone);
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
      if (total != null && ConsoleStatusLine.hasExclusiveStatus(out)) {
        return;
      }
      render(currentLabel, currentDone);
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
    return colors
        ? renderFiniteFancy(label, done, total, frame)
        : renderFiniteAscii(label, done, total, frame);
  }

  static String renderIndeterminate(String label, int frame, boolean colors) {
    return colors ? renderIndeterminateFancy(label, frame) : renderIndeterminateAscii(label, frame);
  }

  static String renderComplete(String label, boolean colors) {
    return colors ? renderCompleteFancy(label) : renderCompleteAscii(label);
  }

  // ---- Plain ASCII rendering (log files, dumb terminals, NO_COLOR) ----

  private static String renderFiniteAscii(String label, int done, int total, int frame) {
    int percent = total == 0 ? 100 : (int) Math.round(done * 100.0 / total);
    int filled = total == 0 ? BAR_WIDTH : Math.clamp(done * BAR_WIDTH / total, 0, BAR_WIDTH);
    String bar =
        filled >= BAR_WIDTH
            ? "=".repeat(BAR_WIDTH)
            : "=".repeat(filled)
                + ">"
                + Const.Symbols.SPACE.repeat(Math.max(0, BAR_WIDTH - filled - 1));
    return SPINNER[Math.floorMod(frame, SPINNER.length)]
        + Const.Symbols.SPACE
        + formatLabel(label)
        + Const.Symbols.SPACE
        + "["
        + bar
        + "]"
        + Const.Symbols.SPACE
        + percent
        + "%";
  }

  private static String renderIndeterminateAscii(String label, int frame) {
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
    return SPINNER[Math.floorMod(frame, SPINNER.length)]
        + Const.Symbols.SPACE
        + formatLabel(label)
        + Const.Symbols.SPACE
        + "["
        + track
        + "]";
  }

  private static String renderCompleteAscii(String label) {
    return SPINNER[0]
        + Const.Symbols.SPACE
        + formatLabel(label)
        + Const.Symbols.SPACE
        + "["
        + "=".repeat(BAR_WIDTH)
        + "]";
  }

  // ---- Modern Unicode rendering (interactive color terminal) ----

  private static String renderFiniteFancy(String label, int done, int total, int frame) {
    int percent = total == 0 ? 100 : (int) Math.round(done * 100.0 / total);
    double fraction = total == 0 ? 1.0 : (double) done / total;
    return AnsiStyle.spinner(braille(frame), true)
        + Const.Symbols.SPACE
        + AnsiStyle.bold(formatLabel(label), true)
        + Const.Symbols.SPACE
        + smoothBar(fraction)
        + Const.Symbols.SPACE
        + AnsiStyle.muted(formatPercent(percent), true);
  }

  private static String renderIndeterminateFancy(String label, int frame) {
    return AnsiStyle.spinner(braille(frame), true)
        + Const.Symbols.SPACE
        + AnsiStyle.bold(formatLabel(label), true)
        + Const.Symbols.SPACE
        + flowingGlow(frame);
  }

  /**
   * Builds an indeterminate bar as a soft {@link #GLOW} pulse that travels left to right and wraps
   * seamlessly, fading into the track instead of sliding a hard-edged block.
   */
  private static String flowingGlow(int frame) {
    char[] cells = new char[INDETERMINATE_WIDTH];
    boolean[] lit = new boolean[INDETERMINATE_WIDTH];
    java.util.Arrays.fill(cells, EMPTY_BLOCK);
    int head = Math.floorMod(frame, INDETERMINATE_WIDTH);
    for (int k = 0; k < GLOW.length; k++) {
      int idx = Math.floorMod(head + k, INDETERMINATE_WIDTH);
      cells[idx] = GLOW[k];
      lit[idx] = true;
    }
    StringBuilder bar = new StringBuilder();
    int i = 0;
    while (i < INDETERMINATE_WIDTH) {
      boolean run = lit[i];
      StringBuilder segment = new StringBuilder();
      while (i < INDETERMINATE_WIDTH && lit[i] == run) {
        segment.append(cells[i]);
        i++;
      }
      bar.append(
          run
              ? AnsiStyle.progress(segment.toString(), true)
              : AnsiStyle.track(segment.toString(), true));
    }
    return bar.toString();
  }

  private static String renderCompleteFancy(String label) {
    return AnsiStyle.success(CHECK_MARK, true)
        + Const.Symbols.SPACE
        + AnsiStyle.bold(formatLabel(label), true)
        + Const.Symbols.SPACE
        + AnsiStyle.success(String.valueOf(FULL_BLOCK).repeat(BAR_WIDTH), true);
  }

  /**
   * Builds a {@value #BAR_WIDTH}-cell bar filled in whole cells so the blue fill always sits flush
   * against the gray track with no sub-cell glyph between them.
   */
  private static String smoothBar(double fraction) {
    int full =
        Math.clamp((int) Math.floor(Math.clamp(fraction, 0.0, 1.0) * BAR_WIDTH), 0, BAR_WIDTH);
    int empty = BAR_WIDTH - full;
    return AnsiStyle.progress(String.valueOf(FULL_BLOCK).repeat(full), true)
        + AnsiStyle.track(String.valueOf(EMPTY_BLOCK).repeat(empty), true);
  }

  private static String braille(int frame) {
    return BRAILLE[Math.floorMod(frame, BRAILLE.length)];
  }

  private static String formatPercent(int percent) {
    String value = percent + "%";
    return Const.Symbols.SPACE.repeat(Math.max(0, 4 - value.length())) + value;
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
