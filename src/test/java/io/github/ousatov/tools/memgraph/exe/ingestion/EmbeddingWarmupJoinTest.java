package io.github.ousatov.tools.memgraph.exe.ingestion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exe.adapter.JavaLanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.analyze.ParseService;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleProgress;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
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
  void postIngestionProgressStaysVisibleWhileWarmupIsJoining() throws InterruptedException {
    IngestionOrchestrator orchestrator = minimalOrchestrator();
    CompletableFuture<Map<EmbeddingSettings, Integer>> warmup = new CompletableFuture<>();
    orchestrator.registerEmbeddingModelWarmup(warmup);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    ConsoleProgress progress =
        ConsoleProgress.indeterminate(
            "Preparing RAG refresh", out, true, Duration.ofSeconds(60), false);

    CountDownLatch refreshDone = new CountDownLatch(1);
    Thread refresher =
        new Thread(
            () -> {
              orchestrator.refreshChunkEmbeddings(new GraphWriter(null, "test"), false, progress);
              refreshDone.countDown();
            },
            "warmup-join-progress-test");
    refresher.start();
    try {
      assertFalse(
          refreshDone.await(200, TimeUnit.MILLISECONDS),
          "refresh must block until the warm-up completes");
      assertTrue(ConsoleStatusLine.hasExclusiveStatus(out));
      assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Preparing RAG refresh"));

      warmup.complete(Map.of());

      assertTrue(refreshDone.await(5, TimeUnit.SECONDS), "refresh must finish after completion");
      assertFalse(ConsoleStatusLine.hasActiveStatus(out));
    } finally {
      warmup.complete(Map.of());
      refresher.join();
    }
  }

  @Test
  void postIngestionProgressTransitionsIntoCodeRefresh() throws ReflectiveOperationException {
    IngestionOrchestrator orchestrator = minimalOrchestrator();
    setCodeEmbeddings(orchestrator, EmbeddingSettings.codeDefaults());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    ConsoleProgress progress =
        ConsoleProgress.indeterminate(
            "Preparing RAG refresh", out, true, Duration.ofSeconds(60), false);

    assertDoesNotThrow(
        () -> orchestrator.refreshChunkEmbeddings(new GraphWriter(null, "test"), false, progress));

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Preparing RAG refresh"));
    assertTrue(output.contains("Refreshing RAG (CodeChunk)"));
    assertFalse(ConsoleStatusLine.hasActiveStatus(out));
  }

  @Test
  void postIngestionProgressRendersWithoutInteractiveConsole() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ConsoleProgress progress = IngestionOrchestrator.openPostIngestionProgress(out, false)) {
      assertNotNull(progress);
    }

    assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Preparing RAG refresh"));
  }

  @Test
  void postIngestionProgressIsOpenedForInitialWatchStartup() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ConsoleProgress progress = IngestionOrchestrator.openPostIngestionProgress(out, true)) {
      assertNotNull(progress);
      assertTrue(ConsoleStatusLine.hasExclusiveStatus(out));
    }

    assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Preparing RAG refresh"));
    assertFalse(ConsoleStatusLine.hasActiveStatus(out));
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

  private static void setCodeEmbeddings(
      IngestionOrchestrator orchestrator, EmbeddingSettings settings)
      throws ReflectiveOperationException {
    Field field = IngestionOrchestrator.class.getDeclaredField("codeEmbeddings");
    field.setAccessible(true);
    field.set(orchestrator, settings);
  }
}
