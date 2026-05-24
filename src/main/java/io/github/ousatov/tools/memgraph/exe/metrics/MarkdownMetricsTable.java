package io.github.ousatov.tools.memgraph.exe.metrics;

import java.util.List;

/**
 * Renders two-column metric tables with stable Markdown alignment.
 *
 * @author Oleksii Usatov
 */
final class MarkdownMetricsTable {

  private static final String METRIC_COLUMN = "metric";
  private static final String VALUE_COLUMN = "value";
  private static final String LF = "\n";

  private MarkdownMetricsTable() {
    throw new UnsupportedOperationException("Utility class");
  }

  static String render(String title, List<Row> rows) {
    int metricWidth =
        rows.stream().map(Row::name).mapToInt(String::length).max().orElse(METRIC_COLUMN.length());
    metricWidth = Math.max(metricWidth, METRIC_COLUMN.length());
    int valueWidth =
        rows.stream().map(Row::value).mapToInt(String::length).max().orElse(VALUE_COLUMN.length());
    valueWidth = Math.max(valueWidth, VALUE_COLUMN.length());
    StringBuilder table = new StringBuilder("# ").append(title).append(LF).append(LF);
    table
        .append("| ")
        .append(padRight(METRIC_COLUMN, metricWidth))
        .append(" | ")
        .append(padLeft(VALUE_COLUMN, valueWidth))
        .append(" |")
        .append(LF);
    table
        .append("|")
        .append("-".repeat(metricWidth + 2))
        .append("|")
        .append("-".repeat(valueWidth + 1))
        .append(":|")
        .append(LF);
    for (Row row : rows) {
      table
          .append("| ")
          .append(padRight(row.name(), metricWidth))
          .append(" | ")
          .append(padLeft(row.value(), valueWidth))
          .append(" |")
          .append(LF);
    }
    return table.toString();
  }

  private static String padLeft(String value, int width) {
    return " ".repeat(Math.max(0, width - value.length())) + value;
  }

  private static String padRight(String value, int width) {
    return value + " ".repeat(Math.max(0, width - value.length()));
  }

  /**
   * One rendered metric row.
   *
   * @param name stable metric identifier
   * @param value rendered value
   * @author Oleksii Usatov
   */
  record Row(String name, String value) {}
}
