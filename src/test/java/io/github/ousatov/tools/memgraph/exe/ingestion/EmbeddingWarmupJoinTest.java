package io.github.ousatov.tools.memgraph.exe.ingestion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exe.adapter.JavaLanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.analyze.ParseService;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the warm-up join contract of {@link IngestionOrchestrator#refreshChunkEmbeddings}:
 * a registered warm-up future must be joined before any embedding work, consumed exactly once, and
 * a warm-up failure must never fail the refresh.
 *
 * @author Oleksii Usatov
 */
class EmbeddingWarmupJoinTest {

  @TempDir private Path tempDir;

  @Test
  void refreshWaitsForWarmupToComplete() throws InterruptedException {
    IngestionOrchestrator orchestrator = minimalOrchestrator();
    CompletableFuture<Map<EmbeddingSettings, Integer>> warmup = new CompletableFuture<>();
    orchestrator.registerEmbeddingModelWarmup(warmup);

    CountDownLatch refreshDone = new CountDownLatch(1);
    Thread refresher =
        new Thread(
            () -> {
              orchestrator.refreshChunkEmbeddings(new GraphWriter(null, "test"), false);
              refreshDone.countDown();
            },
            "warmup-join-test");
    refresher.start();
    try {
      assertFalse(
          refreshDone.await(200, TimeUnit.MILLISECONDS),
          "refresh must block until the warm-up completes");

      warmup.complete(Map.of());

      assertTrue(refreshDone.await(5, TimeUnit.SECONDS), "refresh must finish after completion");
    } finally {
      refresher.join();
    }
  }

  @Test
  void failedWarmupDoesNotFailRefreshAndIsConsumed() {
    IngestionOrchestrator orchestrator = minimalOrchestrator();
    CompletableFuture<Map<EmbeddingSettings, Integer>> warmup = new CompletableFuture<>();
    warmup.completeExceptionally(new IllegalStateException("warm-up broke"));
    orchestrator.registerEmbeddingModelWarmup(warmup);
    GraphWriter writer = new GraphWriter(null, "test");

    assertDoesNotThrow(() -> orchestrator.refreshChunkEmbeddings(writer, false));
    // A second refresh must not re-join the consumed future (watch-mode iterations).
    assertDoesNotThrow(() -> orchestrator.refreshChunkEmbeddings(writer, true));
  }

  private IngestionOrchestrator minimalOrchestrator() {
    ParseService parseService = new ParseService(tempDir, List.of());
    return new IngestionOrchestrator(
        tempDir, "test", 1, null, List.of(new JavaLanguageAdapter(parseService)));
  }
}
