package io.github.ousatov.tools.memgraph.exe.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.vo.ingestion.PreparedWrite;
import io.github.ousatov.tools.memgraph.vo.ingestion.SourceFile;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.Session;
import org.neo4j.driver.async.AsyncSession;
import org.neo4j.driver.async.AsyncTransaction;
import org.neo4j.driver.async.ResultCursor;

/**
 * Unit tests for file transaction batching in {@link IngestionOrchestrator}.
 *
 * @author Oleksii Usatov
 */
class IngestionOrchestratorBatchTest {

  @TempDir private Path tempDir;

  @Test
  void preparedWritesShareOneTransactionWhenAllSucceed() throws Exception {
    RecordingBolt bolt = new RecordingBolt();
    IngestionRunStats stats = new IngestionRunStats(1);
    GraphWriter writer = new GraphWriter(bolt.session(), bolt.asyncSession(), "project", stats, "");
    IngestionOrchestrator orchestrator = orchestrator();

    int failures =
        orchestrator.writePreparedWriteBatch(
            writer,
            List.of(
                preparedWrite("A.java", new TestAdapter(true)),
                preparedWrite("B.java", new TestAdapter(true))));

    assertEquals(0, failures);
    assertEquals(1, bolt.asyncBeginCalls);
    assertEquals(1, bolt.asyncCommitCalls);
    assertEquals(0, bolt.asyncRollbackCalls);
    assertEquals(Cypher.CYPHER_DELETED_SOURCE_FILE_METHOD_NAMES_BATCH, bolt.asyncRunCyphers.get(0));
    assertEquals(
        Cypher.CYPHER_DELETE_STALE_DEFINITION_RELATIONS_FOR_FILES, bolt.asyncRunCyphers.get(1));
    assertEquals(Cypher.CYPHER_DELETE_STALE_DEFINITIONS_FOR_FILES, bolt.asyncRunCyphers.get(2));
    assertEquals("2", metricValue(stats, "files.ingested"));
    assertEquals("0", metricValue(stats, "files.failed"));
  }

  @Test
  void preparedWriteBatchSplitsAndRecordsOnlyFailingFile() throws Exception {
    RecordingBolt bolt = new RecordingBolt();
    IngestionRunStats stats = new IngestionRunStats(1);
    GraphWriter writer = new GraphWriter(bolt.session(), bolt.asyncSession(), "project", stats, "");
    IngestionOrchestrator orchestrator = orchestrator();

    int failures =
        orchestrator.writePreparedWriteBatch(
            writer,
            List.of(
                preparedWrite("A.java", new TestAdapter(true)),
                preparedWrite("B.java", new TestAdapter(false))));

    assertEquals(1, failures);
    assertEquals(3, bolt.asyncBeginCalls);
    assertEquals(1, bolt.asyncCommitCalls);
    assertEquals(2, bolt.asyncRollbackCalls);
    assertEquals("1", metricValue(stats, "files.ingested"));
    assertEquals("1", metricValue(stats, "files.failed"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void customModuleBatchSizeControlsBatchParsingGroups() throws Exception {
    BatchAdapter adapter = new BatchAdapter();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(tempDir, "project", 1, 2, null, List.of(adapter));
    List<SourceFile> files =
        List.of(
            new SourceFile(tempDir.resolve("A.js"), adapter),
            new SourceFile(tempDir.resolve("B.js"), adapter),
            new SourceFile(tempDir.resolve("C.js"), adapter),
            new SourceFile(tempDir.resolve("D.js"), adapter),
            new SourceFile(tempDir.resolve("E.js"), adapter));
    Method preparationBatches =
        IngestionOrchestrator.class.getDeclaredMethod("preparationBatches", List.class);
    preparationBatches.setAccessible(true);

    List<List<SourceFile>> batches =
        (List<List<SourceFile>>) preparationBatches.invoke(orchestrator, files);

    assertEquals(List.of(2, 2, 1), batches.stream().map(List::size).toList());
  }

  private IngestionOrchestrator orchestrator() {
    return new IngestionOrchestrator(tempDir, "project", 1, null, new TestAdapter(true));
  }

  private PreparedWrite<String> preparedWrite(String name, TestAdapter adapter) throws Exception {
    Path file = Files.createFile(tempDir.resolve(name));
    return new PreparedWrite<>(file, adapter, name, SourceFileDefinitions.empty());
  }

  private static String metricValue(IngestionRunStats stats, String name) {
    return stats.snapshot().rows().stream()
        .filter(row -> name.equals(row.name()))
        .map(row -> row.value())
        .findFirst()
        .orElseThrow(() -> new AssertionError("metric not found: " + name));
  }

  private record TestAdapter(boolean writeSucceeds) implements LanguageAdapter<String> {
    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVA;
    }

    @Override
    public boolean accepts(Path file) {
      return true;
    }

    @Override
    public Optional<String> parse(Path file) {
      return Optional.of(file.toString());
    }

    @Override
    public SourceFileDefinitions collectDefinitions(String parsed) {
      return SourceFileDefinitions.empty();
    }

    @Override
    public boolean write(GraphWriter writer, Path file, String parsed) {
      if (!writeSucceeds) {
        return false;
      }
      writer.upsertFile(file, SourceLanguage.JAVA);
      return true;
    }
  }

  private record BatchAdapter() implements LanguageAdapter<String> {
    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVASCRIPT;
    }

    @Override
    public boolean accepts(Path file) {
      return true;
    }

    @Override
    public Optional<String> parse(Path file) {
      return Optional.of(file.toString());
    }

    @Override
    public boolean supportsBatchParsing() {
      return true;
    }

    @Override
    public SourceFileDefinitions collectDefinitions(String parsed) {
      return SourceFileDefinitions.empty();
    }

    @Override
    public boolean write(GraphWriter writer, Path file, String parsed) {
      return true;
    }
  }

  private static final class RecordingBolt {
    private final List<String> asyncRunCyphers = new ArrayList<>();
    private int syncBeginCalls;
    private int asyncBeginCalls;
    private int asyncCommitCalls;
    private int asyncRollbackCalls;

    Session session() {
      return proxy(
          Session.class,
          (proxy, method, args) -> {
            if ("beginTransaction".equals(method.getName())) {
              syncBeginCalls++;
              throw new AssertionError("sync transaction should not be opened");
            }
            return defaultValue(proxy, method);
          });
    }

    AsyncSession asyncSession() {
      return proxy(
          AsyncSession.class,
          (proxy, method, args) -> {
            if ("beginTransactionAsync".equals(method.getName())) {
              asyncBeginCalls++;
              return CompletableFuture.completedFuture(asyncTransaction());
            }
            return defaultValue(proxy, method);
          });
    }

    private AsyncTransaction asyncTransaction() {
      return proxy(
          AsyncTransaction.class,
          (proxy, method, args) -> {
            return switch (method.getName()) {
              case "runAsync" -> {
                asyncRunCyphers.add((String) args[0]);
                yield CompletableFuture.completedFuture(resultCursor());
              }
              case "commitAsync" -> {
                asyncCommitCalls++;
                yield CompletableFuture.completedFuture(null);
              }
              case "rollbackAsync" -> {
                asyncRollbackCalls++;
                yield CompletableFuture.completedFuture(null);
              }
              default -> defaultValue(proxy, method);
            };
          });
    }

    private ResultCursor resultCursor() {
      return proxy(
          ResultCursor.class,
          (proxy, method, args) -> {
            if ("consumeAsync".equals(method.getName())) {
              return CompletableFuture.completedFuture(null);
            }
            if ("listAsync".equals(method.getName())) {
              return CompletableFuture.completedFuture(List.of());
            }
            return defaultValue(proxy, method);
          });
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Object proxy, Method method) {
    return switch (method.getName()) {
      case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName() + " proxy";
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> false;
      default -> null;
    };
  }
}
