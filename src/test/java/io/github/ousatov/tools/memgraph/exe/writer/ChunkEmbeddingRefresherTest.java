package io.github.ousatov.tools.memgraph.exe.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import io.github.ousatov.tools.memgraph.vo.writer.EmbeddingProgressListener;
import io.github.ousatov.tools.memgraph.vo.writer.EmbeddingRefreshResult;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Values;

/**
 * Unit tests for {@link ChunkEmbeddingRefresher}.
 *
 * @author Oleksii Usatov
 */
class ChunkEmbeddingRefresherTest {

  @Test
  void batchCypherKeepsResourceWhenProcedureMemoryLimitIsDisabled() {
    EmbeddingSettings settings = settingsWithProcedureMemory(0);

    String cypher = ChunkEmbeddingRefresher.batchCypher(settings, EmbeddingTarget.CODE);

    assertEquals(EmbeddingTarget.CODE.batchCypher(), cypher);
  }

  @Test
  void batchCypherAddsProcedureMemoryLimitBeforeYield() {
    EmbeddingSettings settings = settingsWithProcedureMemory(1024);

    String cypher = ChunkEmbeddingRefresher.batchCypher(settings, EmbeddingTarget.CODE);

    assertTrue(
        cypher.contains(
            "CALL embeddings.node_sentence(chunks, $config) PROCEDURE MEMORY LIMIT 1024 MB\n"
                + "YIELD success, dimension"));
  }

  @Test
  void derivesStableProjectScopedIndexNamesAndLabels() {
    String indexName = ChunkEmbeddingRefresher.projectVectorIndexName("idx", "My Project!");
    String label =
        ChunkEmbeddingRefresher.projectVectorIndexLabel(EmbeddingTarget.CODE, "My Project!");

    assertEquals("idx_p_my_project_cfad424950cd", indexName);
    assertEquals("CodeChunkEmbedding_p_my_project_cfad424950cd", label);
  }

  @Test
  void batchesMetadataUpdatesAcrossMultipleEmbeddingBatches() {
    int totalChunks = 1000;
    int batchSize = 100;
    int dimension = 128;
    EmbeddingSettings settings =
        new EmbeddingSettings(
            true, "idx", "model", "cos", "f16", batchSize, 48, "", dimension, 0, 0, 0, 0, false);
    RecordingCypherExecutor cypher = new RecordingCypherExecutor(totalChunks);
    ChunkEmbeddingRefresher refresher = new ChunkEmbeddingRefresher(cypher, "project");
    AtomicLong progressCalls = new AtomicLong();

    long embedded =
        refresher.refreshMarkedChunks(
            settings,
            EmbeddingTarget.CODE,
            dimension,
            totalChunks,
            (_, _) -> progressCalls.incrementAndGet());

    assertEquals(totalChunks, embedded);
    assertEquals(totalChunks / batchSize, progressCalls.get());
    // Threshold is clamp(batchSize * 4, 256, 2048) = 400. 1000 chunks in batches of 100:
    // flush at 400, flush at 800, final flush at 1000 => 3 metadata updates instead of 10.
    assertEquals(3, cypher.metadataUpdateCount());
    assertEquals(0, cypher.dirtyCount());
  }

  @Test
  void flushesPendingMetadataBeforeRetryingFailedBatch() {
    int totalChunks = 500;
    int batchSize = 100;
    int dimension = 128;
    EmbeddingSettings settings =
        new EmbeddingSettings(
            true, "idx", "model", "cos", "f16", batchSize, 48, "", dimension, 0, 0, 0, 0, false);
    FailingThenRecoveringCypherExecutor cypher =
        new FailingThenRecoveringCypherExecutor(totalChunks, 2, 1);
    ChunkEmbeddingRefresher refresher = new ChunkEmbeddingRefresher(cypher, "project");

    long embedded =
        refresher.refreshMarkedChunks(
            settings, EmbeddingTarget.CODE, dimension, totalChunks, EmbeddingProgressListener.NONE);

    assertEquals(totalChunks, embedded);
    assertEquals("batch:true:100", cypher.events().get(0));
    assertEquals("batch:true:100", cypher.events().get(1));
    assertEquals("batch:false:100", cypher.events().get(2));
    assertEquals("metadata:200", cypher.events().get(3));
    assertEquals(0, cypher.dirtyCount());
  }

  @Test
  void requiredDirtyOnlyRefreshDoesNotRunStaleBackfillWhenModelIsUnchanged() {
    int dimension = 128;
    EmbeddingSettings settings =
        new EmbeddingSettings(
            true, "idx", "model", "cos", "f16", 100, 48, "", dimension, 0, 0, 0, 1000, true);
    RequiredDirtyOnlyCypherExecutor cypher = new RequiredDirtyOnlyCypherExecutor(dimension);
    ChunkEmbeddingRefresher refresher = new ChunkEmbeddingRefresher(cypher, "project");
    refresher.seedDimension(settings, dimension);

    EmbeddingRefreshResult result =
        refresher.refresh(settings, EmbeddingTarget.CODE, true, EmbeddingProgressListener.NONE);

    assertEquals(1, result.embedded());
    assertEquals(1, cypher.countDirtyReads());
    assertEquals(0, cypher.markStaleReads());
  }

  @Test
  void dirtyOnlyRefreshWithCurrentStateSkipsProjectWideCleanup() {
    int dimension = 128;
    EmbeddingSettings settings =
        new EmbeddingSettings(
            true, "idx", "model", "cos", "f16", 100, 48, "", dimension, 0, 0, 0, 1000, true);
    NoDirtyCurrentStateCypherExecutor cypher = new NoDirtyCurrentStateCypherExecutor(dimension);
    ChunkEmbeddingRefresher refresher = new ChunkEmbeddingRefresher(cypher, "project");
    refresher.seedDimension(settings, dimension);

    EmbeddingRefreshResult result =
        refresher.refresh(settings, EmbeddingTarget.CODE, true, EmbeddingProgressListener.NONE);

    assertEquals(0, result.embedded());
    assertEquals(1, cypher.countDirtyReads());
    assertEquals(1, cypher.refreshStateReads());
    assertEquals(1, cypher.vectorIndexReads());
    assertEquals(0, cypher.projectWideReads());
  }

  private static EmbeddingSettings settingsWithProcedureMemory(int procedureMemoryMb) {
    return new EmbeddingSettings(
        true, "idx", "model", "cos", "f16", 128, 12, "", 0, 0, 0, procedureMemoryMb, 0, true);
  }

  /** Fake {@link CypherExecutor} that keeps rows dirty until metadata updates clear them. */
  private abstract static class FakeCypherExecutor extends CypherExecutor {

    protected final List<String> remainingIds;
    protected final List<String> metadataUpdateCyphers = new ArrayList<>();
    protected final List<String> events = new ArrayList<>();

    FakeCypherExecutor(int totalChunks) {
      super(null, null, "project", new IngestionRunStats(0));
      this.remainingIds =
          IntStream.range(0, totalChunks)
              .mapToObj(index -> "chunk-" + index)
              .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public void run(String cypher, Map<String, Object> params) {
      if (cypher.contains(Const.Params.EMBEDDING_MODEL)) {
        metadataUpdateCyphers.add(cypher);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) params.get(Const.Params.IDS);
        events.add("metadata:" + ids.size());
        remainingIds.removeAll(new HashSet<>(ids));
      }
    }

    @Override
    public <T> T read(String cypher, Map<String, Object> params, Function<Result, T> mapper) {
      if (cypher.equals(EmbeddingTarget.CODE.batchCypher())) {
        int limit = ((Number) params.get(Const.Params.LIMIT)).intValue();
        @SuppressWarnings("unchecked")
        List<String> excludeIds = (List<String>) params.get(Const.Params.EXCLUDE_IDS);
        return mapper.apply(nextResult(limit, excludeIds));
      }
      throw new AssertionError("Unexpected read query: " + cypher);
    }

    protected abstract Result nextResult(int limit, List<String> excludeIds);

    int metadataUpdateCount() {
      return metadataUpdateCyphers.size();
    }

    int dirtyCount() {
      return remainingIds.size();
    }

    List<String> events() {
      return events;
    }

    List<String> selectDirtyIds(int limit, List<String> excludeIds) {
      Set<String> excluded = new HashSet<>(excludeIds);
      return remainingIds.stream().filter(id -> !excluded.contains(id)).limit(limit).toList();
    }
  }

  /** Returns successful batches until all IDs are consumed. */
  private static final class RecordingCypherExecutor extends FakeCypherExecutor {

    RecordingCypherExecutor(int totalChunks) {
      super(totalChunks);
    }

    @Override
    protected Result nextResult(int limit, List<String> excludeIds) {
      List<String> batchIds = selectDirtyIds(limit, excludeIds);
      events.add("batch:true:" + batchIds.size());
      return fakeResult(batchIds, 128, true);
    }
  }

  /** Fails the requested number of embedding batches, then returns successful batches. */
  private static final class FailingThenRecoveringCypherExecutor extends FakeCypherExecutor {

    private int failuresRemaining;
    private int successesBeforeFailure;

    FailingThenRecoveringCypherExecutor(
        int totalChunks, int successesBeforeFailure, int failuresBeforeSuccess) {
      super(totalChunks);
      this.successesBeforeFailure = successesBeforeFailure;
      this.failuresRemaining = failuresBeforeSuccess;
    }

    @Override
    protected Result nextResult(int limit, List<String> excludeIds) {
      List<String> batchIds = selectDirtyIds(limit, excludeIds);
      if (successesBeforeFailure > 0) {
        successesBeforeFailure--;
        events.add("batch:true:" + batchIds.size());
        return fakeResult(batchIds, 128, true);
      }
      if (failuresRemaining > 0) {
        failuresRemaining--;
        events.add("batch:false:" + batchIds.size());
        return fakeResult(batchIds, 128, false);
      }
      events.add("batch:true:" + batchIds.size());
      return fakeResult(batchIds, 128, true);
    }
  }

  private static final class RequiredDirtyOnlyCypherExecutor extends CypherExecutor {

    private final int dimension;
    private int countDirtyReads;
    private int markStaleReads;

    RequiredDirtyOnlyCypherExecutor(int dimension) {
      super(null, null, "project", new IngestionRunStats(0));
      this.dimension = dimension;
    }

    @Override
    public void run(String cypher, Map<String, Object> params) {
      if (!cypher.equals(EmbeddingTarget.CODE.updateMetadataCypher())
          && !cypher.contains("SET project.codeEmbedding")) {
        throw new AssertionError("Unexpected run query: " + cypher);
      }
    }

    @Override
    public <T> T read(String cypher, Map<String, Object> params, Function<Result, T> mapper) {
      if (cypher.equals(Const.Cypher.CYPHER_SHOW_VECTOR_INDEX_INFO)) {
        return mapper.apply(vectorIndexResult());
      }
      if (cypher.equals(EmbeddingTarget.CODE.countChunksCypher())
          || cypher.equals(EmbeddingTarget.CODE.clearObsoleteCypher())
          || cypher.equals(EmbeddingTarget.CODE.countObsoleteCypher())
          || isTagVectorIndexLabelQuery(cypher)) {
        return mapper.apply(
            countResult(cypher.equals(EmbeddingTarget.CODE.countChunksCypher()) ? 1 : 0));
      }
      if (cypher.equals(EmbeddingTarget.CODE.countDirtyCypher())) {
        countDirtyReads++;
        return mapper.apply(countResult(1));
      }
      if (cypher.equals(EmbeddingTarget.CODE.countStaleCypher())) {
        markStaleReads++;
        throw new AssertionError("dirty-only refresh should not run stale backfill");
      }
      if (cypher.equals(EmbeddingTarget.CODE.batchCypher())) {
        return mapper.apply(fakeResult(List.of("chunk-1"), dimension, true));
      }
      throw new AssertionError("Unexpected read query: " + cypher);
    }

    int countDirtyReads() {
      return countDirtyReads;
    }

    int markStaleReads() {
      return markStaleReads;
    }

    private Result vectorIndexResult() {
      String indexName = ChunkEmbeddingRefresher.projectVectorIndexName("idx", "project");
      String indexLabel =
          ChunkEmbeddingRefresher.projectVectorIndexLabel(EmbeddingTarget.CODE, "project");
      return fakeRecordResult(
          Map.of(
              "index_name",
              Values.value(indexName),
              Const.Params.LABEL,
              Values.value(indexLabel),
              Const.Params.PROPERTY,
              Values.value(EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY),
              Const.Rag.DIMENSION,
              Values.value(dimension),
              Const.Rag.CAPACITY,
              Values.value(1000),
              Const.Rag.METRIC,
              Values.value("cos"),
              Const.Rag.SCALAR_KIND,
              Values.value("f16")));
    }
  }

  private static final class NoDirtyCurrentStateCypherExecutor extends CypherExecutor {

    private final int dimension;
    private int countDirtyReads;
    private int refreshStateReads;
    private int vectorIndexReads;
    private int projectWideReads;

    NoDirtyCurrentStateCypherExecutor(int dimension) {
      super(null, null, "project", new IngestionRunStats(0));
      this.dimension = dimension;
    }

    @Override
    public void run(String cypher, Map<String, Object> params) {
      throw new AssertionError("No-dirty fast path should not write: " + cypher);
    }

    @Override
    public <T> T read(String cypher, Map<String, Object> params, Function<Result, T> mapper) {
      if (cypher.equals(EmbeddingTarget.CODE.countDirtyCypher())) {
        countDirtyReads++;
        return mapper.apply(countResult(0));
      }
      if (cypher.contains(" AS current")) {
        refreshStateReads++;
        return mapper.apply(fakeRecordResult(Map.of("current", Values.value(true))));
      }
      if (cypher.equals(Const.Cypher.CYPHER_SHOW_VECTOR_INDEX_INFO)) {
        vectorIndexReads++;
        return mapper.apply(vectorIndexResult());
      }
      if (cypher.equals(EmbeddingTarget.CODE.clearObsoleteCypher())
          || cypher.equals(EmbeddingTarget.CODE.countChunksCypher())
          || cypher.equals(EmbeddingTarget.CODE.countStaleCypher())
          || isTagVectorIndexLabelQuery(cypher)) {
        projectWideReads++;
        throw new AssertionError("No-dirty fast path should skip project-wide query: " + cypher);
      }
      throw new AssertionError("Unexpected read query: " + cypher);
    }

    int countDirtyReads() {
      return countDirtyReads;
    }

    int refreshStateReads() {
      return refreshStateReads;
    }

    int vectorIndexReads() {
      return vectorIndexReads;
    }

    int projectWideReads() {
      return projectWideReads;
    }

    private Result vectorIndexResult() {
      String indexName = ChunkEmbeddingRefresher.projectVectorIndexName("idx", "project");
      String indexLabel =
          ChunkEmbeddingRefresher.projectVectorIndexLabel(EmbeddingTarget.CODE, "project");
      return fakeRecordResult(
          Map.of(
              "index_name",
              Values.value(indexName),
              Const.Params.LABEL,
              Values.value(indexLabel),
              Const.Params.PROPERTY,
              Values.value(EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY),
              Const.Rag.DIMENSION,
              Values.value(dimension),
              Const.Rag.CAPACITY,
              Values.value(1000),
              Const.Rag.METRIC,
              Values.value("cos"),
              Const.Rag.SCALAR_KIND,
              Values.value("f16")));
    }
  }

  private static Result countResult(long count) {
    return fakeRecordResult(Map.of(Const.Params.COUNT, Values.value(count)));
  }

  private static boolean isTagVectorIndexLabelQuery(String cypher) {
    return cypher.contains("SET chunk:CodeChunkEmbedding_p_project_")
        && cypher.contains("RETURN count(chunk) AS count");
  }

  private static Result fakeRecordResult(Map<String, org.neo4j.driver.Value> values) {
    Record recordProxy =
        proxy(
            Record.class,
            (proxy, method, args) -> {
              if ("get".equals(method.getName()) && args.length == 1 && args[0] instanceof String) {
                org.neo4j.driver.Value value = values.get((String) args[0]);
                if (value != null) {
                  return value;
                }
                throw new AssertionError("Unexpected record key: " + args[0]);
              }
              return defaultValue(proxy, method);
            });
    boolean[] consumed = {false};
    return proxy(
        Result.class,
        (proxy, method, _) ->
            switch (method.getName()) {
              case "hasNext" -> !consumed[0];
              case "next", "single" -> {
                consumed[0] = true;
                yield recordProxy;
              }
              default -> defaultValue(proxy, method);
            });
  }

  private static Result fakeResult(List<String> ids, int dimension, boolean success) {
    Record recordProxy =
        proxy(
            Record.class,
            (proxy, method, args) -> {
              if ("get".equals(method.getName()) && args.length == 1 && args[0] instanceof String) {
                return switch ((String) args[0]) {
                  case "success" -> Values.value(success);
                  case "dimension" -> Values.value(dimension);
                  case "ids" -> Values.value(ids);
                  default -> throw new AssertionError("Unexpected record key: " + args[0]);
                };
              }
              return defaultValue(proxy, method);
            });
    boolean[] consumed = {false};
    return proxy(
        Result.class,
        (proxy, method, _) ->
            switch (method.getName()) {
              case "hasNext" -> !consumed[0];
              case "next", "single" -> {
                consumed[0] = true;
                yield recordProxy;
              }
              default -> defaultValue(proxy, method);
            });
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
