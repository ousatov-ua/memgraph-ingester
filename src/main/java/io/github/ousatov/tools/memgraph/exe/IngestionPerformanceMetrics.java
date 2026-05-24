package io.github.ousatov.tools.memgraph.exe;

import java.util.List;

/**
 * Immutable ingestion performance metrics rendered as a stable Markdown table.
 *
 * @param rows ordered performance rows
 * @author Oleksii Usatov
 */
public record IngestionPerformanceMetrics(List<Row> rows) {

  private static final String TITLE = "Ingestion Performance";

  public IngestionPerformanceMetrics {
    rows = List.copyOf(rows);
  }

  /** Renders performance metrics using the same Markdown table style as graph metrics. */
  public String toMarkdownTable() {
    return MarkdownMetricsTable.render(
        TITLE,
        rows.stream().map(row -> new MarkdownMetricsTable.Row(row.name(), row.value())).toList());
  }

  /**
   * One performance metric row.
   *
   * @param name stable metric identifier
   * @param value rendered metric value
   * @author Oleksii Usatov
   */
  public record Row(String name, String value) {}
}
