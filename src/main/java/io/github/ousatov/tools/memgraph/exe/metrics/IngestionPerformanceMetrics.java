package io.github.ousatov.tools.memgraph.exe.metrics;

import io.github.ousatov.tools.memgraph.vo.metrics.IngestionPerformanceRow;
import io.github.ousatov.tools.memgraph.vo.metrics.MarkdownMetricRow;
import java.util.List;

/**
 * Immutable ingestion performance metrics rendered as a stable Markdown table.
 *
 * @param rows ordered performance rows
 * @author Oleksii Usatov
 */
public record IngestionPerformanceMetrics(List<IngestionPerformanceRow> rows) {

  private static final String TITLE = "Ingestion Performance";

  public IngestionPerformanceMetrics {
    rows = List.copyOf(rows);
  }

  /** Renders performance metrics using the same Markdown table style as graph metrics. */
  public String toMarkdownTable() {
    return MarkdownMetricsTable.render(
        TITLE, rows.stream().map(row -> new MarkdownMetricRow(row.name(), row.value())).toList());
  }
}
