package io.github.ousatov.tools.memgraph.exe.metrics;

import io.github.ousatov.tools.memgraph.vo.metrics.IngestionMetricRow;
import io.github.ousatov.tools.memgraph.vo.metrics.MarkdownMetricRow;
import java.util.List;

/**
 * Immutable post-ingestion metrics rendered as a stable Markdown table.
 *
 * @param rows ordered metric rows
 * @author Oleksii Usatov
 */
public record IngestionMetrics(List<IngestionMetricRow> rows) {

  private static final String TITLE = "Ingestion Metrics";

  public IngestionMetrics {
    rows = List.copyOf(rows);
  }

  /** Renders metrics in a human-readable and machine-friendly table. */
  public String toMarkdownTable() {
    return MarkdownMetricsTable.render(
        TITLE,
        rows.stream()
            .map(row -> new MarkdownMetricRow(row.name(), String.valueOf(row.value())))
            .toList());
  }
}
