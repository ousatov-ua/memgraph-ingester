package io.github.ousatov.tools.memgraph.exe;

import java.util.List;

/**
 * Immutable post-ingestion metrics rendered as a stable Markdown table.
 *
 * @param rows ordered metric rows
 * @author Oleksii Usatov
 */
public record IngestionMetrics(List<Row> rows) {

  private static final String TITLE = "Ingestion Metrics";
  private static final String HEADING = "# " + TITLE;
  private static final String METRIC_COLUMN = "metric";
  private static final String VALUE_COLUMN = "value";
  private static final String LF = "\n";

  public IngestionMetrics {
    rows = List.copyOf(rows);
  }

  /** Renders metrics in a human-readable and machine-friendly table. */
  public String toMarkdownTable() {
    int metricWidth =
        rows.stream().map(Row::name).mapToInt(String::length).max().orElse(METRIC_COLUMN.length());
    metricWidth = Math.max(metricWidth, METRIC_COLUMN.length());
    int valueWidth =
        rows.stream()
            .map(Row::value)
            .map(String::valueOf)
            .mapToInt(String::length)
            .max()
            .orElse(VALUE_COLUMN.length());
    valueWidth = Math.max(valueWidth, VALUE_COLUMN.length());
    StringBuilder table = new StringBuilder(HEADING).append(LF).append(LF);
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
          .append(padLeft(String.valueOf(row.value()), valueWidth))
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
   * One numeric metric row.
   *
   * @param name stable metric identifier
   * @param value metric count
   * @author Oleksii Usatov
   */
  public record Row(String name, long value) {}
}
