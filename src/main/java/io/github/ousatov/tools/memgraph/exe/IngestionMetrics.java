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

  public IngestionMetrics {
    rows = List.copyOf(rows);
  }

  /** Renders metrics in a human-readable and machine-friendly table. */
  public String toMarkdownTable() {
    StringBuilder table = new StringBuilder(TITLE).append(System.lineSeparator());
    table.append("| metric | value |").append(System.lineSeparator());
    table.append("|---|---:|").append(System.lineSeparator());
    for (Row row : rows) {
      table
          .append("| ")
          .append(row.name())
          .append(" | ")
          .append(row.value())
          .append(" |")
          .append(System.lineSeparator());
    }
    return table.toString();
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
