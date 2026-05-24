package io.github.ousatov.tools.memgraph.exe.metrics;

import java.util.List;

/**
 * Immutable post-ingestion metrics rendered as a stable Markdown table.
 *
 * @param rows ordered metric rows
 * @author Oleksii Usatov
 */
public record IngestionMetrics(List<Row> rows) {

  private static final String TITLE = "Ingestion Metrics";

  public IngestionMetrics {
    rows = List.copyOf(rows);
  }

  /** Renders metrics in a human-readable and machine-friendly table. */
  public String toMarkdownTable() {
    return MarkdownMetricsTable.render(
        TITLE,
        rows.stream()
            .map(row -> new MarkdownMetricsTable.Row(row.name(), String.valueOf(row.value())))
            .toList());
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
