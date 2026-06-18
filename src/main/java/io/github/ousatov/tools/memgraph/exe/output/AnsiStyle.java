package io.github.ousatov.tools.memgraph.exe.output;

import java.util.Locale;

/**
 * Applies small ANSI color accents for interactive terminal output.
 *
 * @author Oleksii Usatov
 */
final class AnsiStyle {

  private static final String RESET = "\u001B[0m";
  private static final String BOLD = "\u001B[1m";
  private static final String DIM = "\u001B[2m";
  private static final String GRAY = "\u001B[90m";
  private static final String BRIGHT_BLUE = "\u001B[94m";
  private static final String BRIGHT_CYAN = "\u001B[96m";
  private static final String GREEN = "\u001B[32m";
  private static final String BRIGHT_MAGENTA = "\u001B[95m";

  private AnsiStyle() {
    throw new UnsupportedOperationException("Utility class");
  }

  static boolean colorsEnabled(boolean interactive) {
    return interactive && System.getenv("NO_COLOR") == null && !isDumbTerminal();
  }

  static String bold(String text, boolean enabled) {
    return wrap(text, enabled, BOLD);
  }

  static String frame(String text, boolean enabled) {
    return wrap(text, enabled, BRIGHT_MAGENTA);
  }

  static String hint(String text, boolean enabled) {
    return wrap(text, enabled, BRIGHT_MAGENTA);
  }

  static String progress(String text, boolean enabled) {
    return wrap(text, enabled, BRIGHT_BLUE);
  }

  static String spinner(String text, boolean enabled) {
    return wrap(text, enabled, BRIGHT_CYAN);
  }

  static String success(String text, boolean enabled) {
    return wrap(text, enabled, GREEN);
  }

  /** Muted accent for an empty progress-bar track. */
  static String track(String text, boolean enabled) {
    return wrap(text, enabled, GRAY);
  }

  /** Subdued accent for secondary metrics such as percent and counts. */
  static String muted(String text, boolean enabled) {
    return wrap(text, enabled, DIM);
  }

  private static boolean isDumbTerminal() {
    String term = System.getenv("TERM");
    return term != null && "dumb".equals(term.toLowerCase(Locale.ROOT));
  }

  private static String wrap(String text, boolean enabled, String style) {
    return enabled ? style + text + RESET : text;
  }
}
