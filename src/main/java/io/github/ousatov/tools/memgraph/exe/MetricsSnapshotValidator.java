package io.github.ousatov.tools.memgraph.exe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compares rendered ingestion metrics with an expected snapshot file.
 *
 * @author Oleksii Usatov
 */
public final class MetricsSnapshotValidator {

  private MetricsSnapshotValidator() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Validates that the expected file matches the rendered metrics table. */
  public static void validate(Path expectedFile, String actualMetrics) throws IOException {
    String expectedMetrics = Files.readString(expectedFile, StandardCharsets.UTF_8);
    if (!normalize(expectedMetrics).equals(normalize(actualMetrics))) {
      throw new IllegalStateException(
          "Metrics differ from "
              + expectedFile
              + System.lineSeparator()
              + "Expected:"
              + System.lineSeparator()
              + normalize(expectedMetrics)
              + System.lineSeparator()
              + "Actual:"
              + System.lineSeparator()
              + normalize(actualMetrics));
    }
  }

  private static String normalize(String value) {
    return value.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
  }
}
