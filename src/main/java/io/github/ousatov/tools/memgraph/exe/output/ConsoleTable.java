package io.github.ousatov.tools.memgraph.exe.output;

import io.github.ousatov.tools.memgraph.def.Const;
import java.util.List;

/**
 * Renders a two-column report as a boxed table: rounded and colored on a capable TTY, or plain
 * ASCII otherwise.
 *
 * @author Oleksii Usatov
 */
public final class ConsoleTable {

  /** One labeled value row of the table. */
  public record Row(String label, String value) {}

  private static final String LF = Const.Symbols.NEW_LINE;
  private static final String SP = Const.Symbols.SPACE;

  // Rounded box-drawing glyphs for the color variant.
  private static final String H = "\u2500"; // ─
  private static final String V = "\u2502"; // │
  private static final String TOP_LEFT = "\u256D"; // ╭
  private static final String TOP_RIGHT = "\u256E"; // ╮
  private static final String BOTTOM_LEFT = "\u2570"; // ╰
  private static final String BOTTOM_RIGHT = "\u256F"; // ╯
  private static final String TEE_DOWN = "\u252C"; // ┬
  private static final String TEE_UP = "\u2534"; // ┴
  private static final String TEE_RIGHT = "\u251C"; // ├
  private static final String TEE_LEFT = "\u2524"; // ┤
  private static final String CROSS = "\u253C"; // ┼

  private ConsoleTable() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Renders {@code rows} under {@code title} with the given column headers. */
  public static String render(
      String title, String labelHeader, String valueHeader, List<Row> rows, boolean colors) {
    int labelWidth = width(labelHeader, rows.stream().map(Row::label).toList());
    int valueWidth = width(valueHeader, rows.stream().map(Row::value).toList());
    return colors
        ? renderFancy(title, labelHeader, valueHeader, rows, labelWidth, valueWidth)
        : renderAscii(title, labelHeader, valueHeader, rows, labelWidth, valueWidth);
  }

  private static String renderFancy(
      String title, String labelHeader, String valueHeader, List<Row> rows, int lw, int vw) {
    String labelRule = H.repeat(lw + 2);
    String valueRule = H.repeat(vw + 2);
    StringBuilder table = new StringBuilder();
    table.append(AnsiStyle.bold(title, true)).append(LF);
    table.append(frame(TOP_LEFT + labelRule + TEE_DOWN + valueRule + TOP_RIGHT)).append(LF);
    table
        .append(
            rowFancy(
                AnsiStyle.bold(padRight(labelHeader, lw), true),
                AnsiStyle.bold(padLeft(valueHeader, vw), true)))
        .append(LF);
    table.append(frame(TEE_RIGHT + labelRule + CROSS + valueRule + TEE_LEFT)).append(LF);
    for (Row row : rows) {
      table
          .append(
              rowFancy(
                  padRight(row.label(), lw), AnsiStyle.progress(padLeft(row.value(), vw), true)))
          .append(LF);
    }
    table.append(frame(BOTTOM_LEFT + labelRule + TEE_UP + valueRule + BOTTOM_RIGHT)).append(LF);
    return table.toString();
  }

  private static String renderAscii(
      String title, String labelHeader, String valueHeader, List<Row> rows, int lw, int vw) {
    String rule =
        "+" + Const.Symbols.DASH.repeat(lw + 2) + "+" + Const.Symbols.DASH.repeat(vw + 2) + "+";
    StringBuilder table = new StringBuilder();
    table.append(title).append(LF);
    table.append(rule).append(LF);
    table.append(rowAscii(padRight(labelHeader, lw), padLeft(valueHeader, vw))).append(LF);
    table.append(rule).append(LF);
    for (Row row : rows) {
      table.append(rowAscii(padRight(row.label(), lw), padLeft(row.value(), vw))).append(LF);
    }
    table.append(rule).append(LF);
    return table.toString();
  }

  private static String rowFancy(String label, String value) {
    String bar = AnsiStyle.frame(V, true);
    return bar + SP + label + SP + bar + SP + value + SP + bar;
  }

  private static String rowAscii(String label, String value) {
    return Const.Symbols.PIPE
        + SP
        + label
        + SP
        + Const.Symbols.PIPE
        + SP
        + value
        + SP
        + Const.Symbols.PIPE;
  }

  private static String frame(String text) {
    return AnsiStyle.frame(text, true);
  }

  private static int width(String header, List<String> values) {
    int widest = values.stream().mapToInt(String::length).max().orElse(0);
    return Math.max(widest, header.length());
  }

  private static String padLeft(String value, int width) {
    return SP.repeat(Math.max(0, width - value.length())) + value;
  }

  private static String padRight(String value, int width) {
    return value + SP.repeat(Math.max(0, width - value.length()));
  }
}
