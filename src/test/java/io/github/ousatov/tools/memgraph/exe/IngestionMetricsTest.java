package io.github.ousatov.tools.memgraph.exe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for metrics rendering and snapshot comparison.
 *
 * @author Oleksii Usatov
 */
class IngestionMetricsTest {

  @TempDir private Path tempDir;

  @Test
  void rendersStableMarkdownTable() {
    IngestionMetrics metrics =
        new IngestionMetrics(
            List.of(
                new IngestionMetrics.Row("classes.internal", 7),
                new IngestionMetrics.Row("calls", 11)));

    String table = metrics.toMarkdownTable();

    assertEquals(
        """
        Ingestion Metrics
        | metric | value |
        |---|---:|
        | classes.internal | 7 |
        | calls | 11 |
        """,
        table);
  }

  @Test
  void validatesMatchingSnapshot() throws IOException {
    String actualMetrics =
        new IngestionMetrics(List.of(new IngestionMetrics.Row("methods", 3))).toMarkdownTable();
    Path expectedFile = tempDir.resolve("metrics.md");
    Files.writeString(expectedFile, actualMetrics);

    assertDoesNotThrow(() -> MetricsSnapshotValidator.validate(expectedFile, actualMetrics));
  }

  @Test
  void loadsMetricQueriesFromPackagedResources() {
    assertEquals(15, IngestionMetricsCollector.resourceNames().size());
    for (String resourceName : IngestionMetricsCollector.resourceNames()) {
      assertNotNull(
          IngestionMetricsCollector.class.getResource(
              "/io/github/ousatov/tools/memgraph/cypher/metrics/" + resourceName));
    }
  }

  @Test
  void rejectsDifferentSnapshot() throws IOException {
    String actualMetrics =
        new IngestionMetrics(List.of(new IngestionMetrics.Row("methods", 3))).toMarkdownTable();
    Path expectedFile = tempDir.resolve("metrics.md");
    Files.writeString(
        expectedFile,
        new IngestionMetrics(List.of(new IngestionMetrics.Row("methods", 2))).toMarkdownTable());

    assertThrows(
        IllegalStateException.class,
        () -> MetricsSnapshotValidator.validate(expectedFile, actualMetrics));
  }
}
