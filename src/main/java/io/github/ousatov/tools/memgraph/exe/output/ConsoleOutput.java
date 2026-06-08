package io.github.ousatov.tools.memgraph.exe.output;

import io.github.ousatov.tools.memgraph.def.Const;
import java.io.PrintStream;
import java.util.Objects;

/**
 * Entry point for colorful Memgraph ingester console output.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings("java:S106")
public final class ConsoleOutput {

  public static final String TITLE = "Memgraph ingester";

  private static final int TITLE_WIDTH = 56;

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
    line(
        System.err,
        AnsiStyle.success(text, AnsiStyle.colorsEnabled(ConsoleStatusLine.isInteractive())));
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

  /** Prints the application title banner to the given stream. */
  public static void printTitle(PrintStream out, boolean interactive) {
    PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
    boolean colors = AnsiStyle.colorsEnabled(interactive);
    String border = "+" + "-".repeat(TITLE_WIDTH) + "+";
    ConsoleStatusLine.line(stream, AnsiStyle.frame(border, colors));
    ConsoleStatusLine.line(
        stream,
        AnsiStyle.frame("|", colors)
            + AnsiStyle.bold(center(TITLE, TITLE_WIDTH), colors)
            + AnsiStyle.frame("|", colors));
    ConsoleStatusLine.line(
        stream,
        AnsiStyle.frame("|", colors)
            + center("version " + Const.Cli.VERSION, TITLE_WIDTH)
            + AnsiStyle.frame("|", colors));
    ConsoleStatusLine.line(stream, AnsiStyle.frame(border, colors));
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
}
