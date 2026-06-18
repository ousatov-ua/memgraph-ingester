package io.github.ousatov.tools.memgraph.exe.output;

import io.github.ousatov.tools.memgraph.def.Const;
import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

/**
 * Entry point for colorful Memgraph ingester console output.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings("java:S106")
public final class ConsoleOutput {

  public static final String TITLE = "Memgraph ingester";

  private static final String HIDE_CURSOR = "\u001B[?25l";
  private static final String SHOW_CURSOR = "\u001B[?25h";
  private static final int TITLE_WIDTH = 56;
  private static final String CHECK_MARK = "\u2713 "; // ✓

  private ConsoleOutput() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Prints the application title banner to the error console. */
  public static void printTitle() {
    printTitle(System.err, ConsoleStatusLine.isInteractive());
  }

  /** Prints a persistent console line, preserving any active status first. */
  public static void line(String text) {
    line(System.err, text);
  }

  /** Prints a persistent console line on the given stream. */
  public static void line(PrintStream out, String text) {
    PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
    Objects.requireNonNull(text, Const.Params.TEXT);
    ConsoleStatusLine.withFinishedLine(stream, () -> stream.println(text));
  }

  /** Prints a colored persistent hint line on the process error stream. */
  public static void hint(String text) {
    line(
        System.err,
        AnsiStyle.hint(text, AnsiStyle.colorsEnabled(ConsoleStatusLine.isInteractive())));
  }

  /** Prints a colored persistent success line on the process error stream. */
  public static void success(String text) {
    boolean colors = AnsiStyle.colorsEnabled(ConsoleStatusLine.isInteractive());
    String prefix = colors ? CHECK_MARK : Const.Symbols.EMPTY;
    line(System.err, AnsiStyle.success(prefix + text, colors));
  }

  /** Rewrites the active console status line. */
  public static void status(String text) {
    status(System.err, text);
  }

  /** Rewrites the active console status line on the given stream. */
  public static void status(PrintStream out, String text) {
    ConsoleStatusLine.update(
        Objects.requireNonNull(out, Const.Params.OUT),
        Objects.requireNonNull(text, Const.Params.TEXT));
  }

  /** Finishes the active process error status line, if any. */
  public static void finishStatus() {
    ConsoleStatusLine.finish(System.err);
  }

  /** Installs a shutdown hook that restores the terminal cursor if the JVM exits early. */
  public static Thread installCursorRestoreHook() {
    Thread hook = cursorRestoreHook(System.err, ConsoleStatusLine.isInteractive());
    Runtime.getRuntime().addShutdownHook(hook);
    return hook;
  }

  /** Hides the terminal cursor for interactive console output. */
  public static void hideCursor() {
    hideCursor(System.err, ConsoleStatusLine.isInteractive());
  }

  static void hideCursor(PrintStream out, boolean interactive) {
    setCursorVisible(out, interactive, false);
  }

  /** Restores the terminal cursor for interactive console output. */
  public static void showCursor() {
    showCursor(System.err, ConsoleStatusLine.isInteractive());
  }

  static void showCursor(PrintStream out, boolean interactive) {
    setCursorVisible(out, interactive, true);
  }

  static Thread cursorRestoreHook(PrintStream out, boolean interactive) {
    PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
    return new Thread(() -> showCursor(stream, interactive), "memgraph-console-cursor-restore");
  }

  /** Prints the application title banner to the given stream. */
  public static void printTitle(PrintStream out, boolean interactive) {
    PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
    boolean colors = AnsiStyle.colorsEnabled(interactive);
    if (colors) {
      printRoundedTitle(stream);
    } else {
      printAsciiTitle(stream);
    }
  }

  private static void printAsciiTitle(PrintStream stream) {
    String border = "+" + "-".repeat(TITLE_WIDTH) + "+";
    ConsoleStatusLine.line(stream, border);
    ConsoleStatusLine.line(stream, "|" + center(TITLE, TITLE_WIDTH) + "|");
    ConsoleStatusLine.line(stream, "|" + center("version " + Const.Cli.VERSION, TITLE_WIDTH) + "|");
    ConsoleStatusLine.line(stream, border);
  }

  private static void printRoundedTitle(PrintStream stream) {
    String dashes = "\u2500".repeat(TITLE_WIDTH); // ─
    String left = AnsiStyle.frame("\u2502", true); // │
    String right = AnsiStyle.frame("\u2502", true);
    ConsoleStatusLine.line(stream, AnsiStyle.frame("\u256D" + dashes + "\u256E", true)); // ╭ ╮
    ConsoleStatusLine.line(stream, left + AnsiStyle.bold(center(TITLE, TITLE_WIDTH), true) + right);
    ConsoleStatusLine.line(
        stream,
        left + AnsiStyle.muted(center("version " + Const.Cli.VERSION, TITLE_WIDTH), true) + right);
    ConsoleStatusLine.line(stream, AnsiStyle.frame("\u2570" + dashes + "\u256F", true)); // ╰ ╯
  }

  /**
   * Renders a two-column report as a boxed table, colored when the terminal supports it and plain
   * ASCII otherwise.
   */
  public static String table(
      String title, String labelHeader, String valueHeader, List<ConsoleTable.Row> rows) {
    return ConsoleTable.render(
        title,
        labelHeader,
        valueHeader,
        rows,
        AnsiStyle.colorsEnabled(ConsoleStatusLine.isInteractive()));
  }

  /** Opens a finite progress indicator. */
  public static ConsoleProgress progress(String label, int total) {
    return ConsoleProgress.finite(label, total);
  }

  /** Opens an indeterminate progress indicator for work without a known total. */
  public static ConsoleProgress indeterminateProgress(String label) {
    return ConsoleProgress.indeterminate(label);
  }

  private static String center(String text, int width) {
    int padding = Math.max(0, width - text.length());
    int left = padding / 2;
    int right = padding - left;
    return Const.Symbols.SPACE.repeat(left) + text + Const.Symbols.SPACE.repeat(right);
  }

  private static void setCursorVisible(PrintStream out, boolean interactive, boolean visible) {
    PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
    if (!interactive) {
      return;
    }
    stream.print(visible ? SHOW_CURSOR : HIDE_CURSOR);
    stream.flush();
  }
}
