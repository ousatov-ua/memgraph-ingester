package io.github.ousatov.tools.memgraph.exe.metrics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
                new io.github.ousatov.tools.memgraph.vo.metrics.IngestionMetricRow(
                    "classes.internal", 7),
                new io.github.ousatov.tools.memgraph.vo.metrics.IngestionMetricRow("calls", 1061),
                new io.github.ousatov.tools.memgraph.vo.metrics.IngestionMetricRow(
                    "resolved_code_refs", 16)));

    String table = metrics.toMarkdownTable();

    assertEquals(
        """
        # Ingestion Metrics

        | metric             | value |
        |--------------------|------:|
        | classes.internal   |     7 |
        | calls              |  1061 |
        | resolved_code_refs |    16 |
        """,
        table);
  }

  @Test
  void rendersStablePerformanceMarkdownTable() {
    IngestionPerformanceMetrics metrics =
        new IngestionPerformanceMetrics(
            List.of(
                new io.github.ousatov.tools.memgraph.vo.metrics.IngestionPerformanceRow(
                    "files.total", "51"),
                new io.github.ousatov.tools.memgraph.vo.metrics.IngestionPerformanceRow(
                    "duration.ms", "1234"),
                new io.github.ousatov.tools.memgraph.vo.metrics.IngestionPerformanceRow(
                    "cypher.statements", "987")));

    String table = metrics.toMarkdownTable();

    assertEquals(
        """
        # Ingestion Performance

        | metric            | value |
        |-------------------|------:|
        | files.total       |    51 |
        | duration.ms       |  1234 |
        | cypher.statements |   987 |
        """,
        table);
  }

  @Test
  void runStatsSnapshotIncludesPhaseScopeAndTopCypherRows() {
    IngestionRunStats stats = new IngestionRunStats(2);
    stats.recordPhaseNanos(IngestionRunStats.PHASE_PARSE, TimeUnit.MILLISECONDS.toNanos(12));
    stats.recordPhaseNanos(IngestionRunStats.PHASE_WRITE, TimeUnit.MILLISECONDS.toNanos(5));
    stats.recordCypherStatement(
        "MATCH (n:File {project: $project}) RETURN count(n)", TimeUnit.MILLISECONDS.toNanos(3));
    stats.recordCypherBatch(
        "UNWIND $rows AS row MERGE (m:Method {signature: row.sig, project: $project})",
        7,
        TimeUnit.MILLISECONDS.toNanos(9));
    stats.recordChangedDefinitions(
        List.of("com.example.Helper", "", "com.example.Helper"),
        List.of("com.example.Caller.run()"));

    IngestionPerformanceMetrics metrics = stats.snapshot();

    assertEquals(List.of("com.example.Caller.run()"), stats.changedCallerSignatures());
    assertEquals(List.of("com.example.Helper"), stats.changedOwnerFqns());
    assertTrue(
        metrics
            .rows()
            .contains(
                new io.github.ousatov.tools.memgraph.vo.metrics.IngestionPerformanceRow(
                    "phase.parse.ms", "12")));
    assertTrue(
        metrics
            .rows()
            .contains(
                new io.github.ousatov.tools.memgraph.vo.metrics.IngestionPerformanceRow(
                    "phase.write.ms", "5")));
    assertTrue(
        metrics.rows().stream()
            .anyMatch(
                row ->
                    "cypher.top.1".equals(row.name())
                        && row.value().startsWith("9 ms, calls=1, rows=7, UNWIND $rows")));
  }

  @Test
  void validatesMatchingSnapshot() throws IOException {
    String actualMetrics =
        new IngestionMetrics(
                List.of(
                    new io.github.ousatov.tools.memgraph.vo.metrics.IngestionMetricRow(
                        "methods", 3)))
            .toMarkdownTable();
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
  void excludesSyntheticMethodsFromPrimaryMethodMetric() throws IOException {
    assertTrue(
        readMetricQuery("methods.cypher").contains("WHERE n.isSynthetic = false"),
        "primary method metric should exclude synthetic methods");
  }

  @Test
  void rejectsNonMarkdownSnapshotEvenWhenTextMatches() throws IOException {
    String metrics =
        """
        Ingestion Metrics
        | metric | value |
        |---|---:|
        | methods | 3 |
        """;
    Path expectedFile = tempDir.resolve("metrics.md");
    Files.writeString(expectedFile, metrics);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> MetricsSnapshotValidator.validate(expectedFile, metrics));

    assertTrue(error.getMessage().contains("Expected metrics must use Markdown table syntax."));
  }

  @Test
  void rejectsDifferentSnapshot() throws IOException {
    String actualMetrics =
        new IngestionMetrics(
                List.of(
                    new io.github.ousatov.tools.memgraph.vo.metrics.IngestionMetricRow(
                        "methods", 3)))
            .toMarkdownTable();
    Path expectedFile = tempDir.resolve("metrics.md");
    Files.writeString(
        expectedFile,
        new IngestionMetrics(
                List.of(
                    new io.github.ousatov.tools.memgraph.vo.metrics.IngestionMetricRow(
                        "methods", 2)))
            .toMarkdownTable());

    assertThrows(
        IllegalStateException.class,
        () -> MetricsSnapshotValidator.validate(expectedFile, actualMetrics));
  }

  private static String readMetricQuery(String resourceName) throws IOException {
    try (InputStream input =
        IngestionMetricsCollector.class.getResourceAsStream(
            "/io/github/ousatov/tools/memgraph/cypher/metrics/" + resourceName)) {
      if (input == null) {
        throw new AssertionError("Missing metrics query resource " + resourceName);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
