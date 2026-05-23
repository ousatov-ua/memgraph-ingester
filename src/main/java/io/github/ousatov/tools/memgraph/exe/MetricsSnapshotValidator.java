package io.github.ousatov.tools.memgraph.exe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compares rendered ingestion metrics with an expected snapshot file.
 *
 * @author Oleksii Usatov
 */
public final class MetricsSnapshotValidator {

  private static final List<String> MARKDOWN_PREFIX =
      List.of("# Ingestion Metrics", "", "| metric | value |", "| --- | ---: |");
  private static final Pattern DATA_ROW_PATTERN = Pattern.compile("\\| [^|]+ \\| \\d+ \\|");

  private MetricsSnapshotValidator() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Validates that the expected file matches the rendered metrics table. */
  public static void validate(Path expectedFile, String actualMetrics) throws IOException {
    String expectedMetrics = normalize(Files.readString(expectedFile, StandardCharsets.UTF_8));
    String renderedMetrics = normalize(actualMetrics);
    validateMarkdownTable("Expected", expectedMetrics);
    validateMarkdownTable("Actual", renderedMetrics);
    if (!expectedMetrics.equals(renderedMetrics)) {
      throw new IllegalStateException(
          "Metrics differ from "
              + expectedFile
              + System.lineSeparator()
              + "Expected:"
              + System.lineSeparator()
              + expectedMetrics
              + System.lineSeparator()
              + "Actual:"
              + System.lineSeparator()
              + renderedMetrics);
    }
  }

  private static void validateMarkdownTable(String label, String metrics) {
    List<String> lines = metrics.lines().toList();
    if (lines.size() < MARKDOWN_PREFIX.size()
        || !lines.subList(0, MARKDOWN_PREFIX.size()).equals(MARKDOWN_PREFIX)) {
      throw new IllegalStateException(label + " metrics must use Markdown table syntax.");
    }
    for (String line : lines.subList(MARKDOWN_PREFIX.size(), lines.size())) {
      if (!DATA_ROW_PATTERN.matcher(line).matches()) {
        throw new IllegalStateException(
            label + " metrics row is not a Markdown table row: " + line);
      }
    }
  }

  private static String normalize(String value) {
    return value.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
  }
}
