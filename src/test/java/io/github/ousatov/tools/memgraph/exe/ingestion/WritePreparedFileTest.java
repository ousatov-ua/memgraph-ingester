package io.github.ousatov.tools.memgraph.exe.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exe.adapter.JavaLanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.analyze.ParseService;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.vo.ingestion.PreparedFailure;
import io.github.ousatov.tools.memgraph.vo.ingestion.PreparedSkip;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link IngestionOrchestrator#writePreparedFile} dispatch. Covers the {@link
 * PreparedFailure} and {@link PreparedSkip} branches without a live Memgraph session, verifying
 * that {@link IngestionRunStats} is updated correctly for each sealed subtype.
 *
 * @author Oleksii Usatov
 */
class WritePreparedFileTest {

  @TempDir private Path tempDir;

  @Test
  void preparedFailureRecordsFailedFileAndReturnsFalse() throws IOException {
    Path file = Files.createFile(tempDir.resolve("fail.java"));
    IngestionRunStats stats = new IngestionRunStats(1);
    GraphWriter writer = new GraphWriter(null, "test", stats);
    IngestionOrchestrator orchestrator = minimalOrchestrator();

    boolean result = orchestrator.writePreparedFile(writer, new PreparedFailure(file));

    assertFalse(result);
    assertEquals("1", metricValue(stats, "files.failed"));
    assertEquals("0", metricValue(stats, "files.skipped"));
  }

  @Test
  void preparedSkipRecordsSkippedFileAndReturnsTrue() throws IOException {
    Path file = Files.createFile(tempDir.resolve("skip.java"));
    IngestionRunStats stats = new IngestionRunStats(1);
    GraphWriter writer = new GraphWriter(null, "test", stats);
    IngestionOrchestrator orchestrator = minimalOrchestrator();

    boolean result = orchestrator.writePreparedFile(writer, new PreparedSkip(file));

    assertTrue(result);
    assertEquals("0", metricValue(stats, "files.failed"));
    assertEquals("1", metricValue(stats, "files.skipped"));
  }

  private static String metricValue(IngestionRunStats stats, String name) {
    return stats.snapshot().rows().stream()
        .filter(row -> name.equals(row.name()))
        .map(row -> row.value())
        .findFirst()
        .orElseThrow(() -> new AssertionError("metric not found: " + name));
  }

  private IngestionOrchestrator minimalOrchestrator() {
    ParseService parseService = new ParseService(tempDir, List.of());
    return new IngestionOrchestrator(
        tempDir, "test", 1, null, List.of(new JavaLanguageAdapter(parseService)));
  }
}
