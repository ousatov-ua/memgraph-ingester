package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.def.Const.Rag;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import io.github.ousatov.tools.memgraph.vo.writer.EmbeddingBatchResult;
import io.github.ousatov.tools.memgraph.vo.writer.EmbeddingRefreshResult;
import io.github.ousatov.tools.memgraph.vo.writer.VectorIndexInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.neo4j.driver.Result;
import org.neo4j.driver.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles embedding refresh for both {@code :CodeChunk} and {@code :MemoryChunk} nodes, using
 * {@link EmbeddingTarget} to parameterise the few differences between the two chunk types.
 *
 * <p>Not thread-safe. Each instance is owned by a single {@link GraphWriter}, which is itself
 * session-scoped (one per thread in parallel ingestion mode).
 *
 * @author Oleksii Usatov
 */
final class ChunkEmbeddingRefresher {

  private static final Logger log = LoggerFactory.getLogger(ChunkEmbeddingRefresher.class);

  private static final Pattern CYPHER_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final CypherExecutor cypher;
  private final Map<String, Integer> dimensionCache = new HashMap<>();

  ChunkEmbeddingRefresher(CypherExecutor cypher) {
    this.cypher = cypher;
  }

  /**
   * Refreshes stale chunk embeddings for the given target type.
   *
   * @param settings embedding configuration
   * @param target either {@link EmbeddingTarget#CODE} or {@link EmbeddingTarget#MEMORY}
   * @param dirtyOnly when {@code true}, limits refresh to chunks marked dirty; ignored when the
   *     target has no dirty-count query
   */
  EmbeddingRefreshResult refresh(
      EmbeddingSettings settings, EmbeddingTarget target, boolean dirtyOnly) {
    if (!settings.enabled()) {
      return new EmbeddingRefreshResult(0L, 0);
    }
    validateCypherIdentifier(settings.indexName(), target.indexNameParam());

    int dimension = embeddingDimension(settings);
    ensureVectorIndex(settings, target, dimension);

    boolean useDirty = dirtyOnly && target.countDirtyCypher() != null;
    long stale = useDirty ? countDirty(target) : countStale(settings, target, dimension);
    long embedded = 0L;
    int batchSize = settings.batchSize();

    while (stale > 0) {
      EmbeddingBatchResult batch = refreshBatch(settings, target, dimension, batchSize);
      if (!batch.success()) {
        if (batchSize == 1) {
          throw embeddingFailure(target, batch.ids());
        }
        int nextBatchSize = Math.max(1, batchSize / 2);
        log.warn(
            "Memgraph embeddings.node_sentence returned false for {} {}(s); retrying with batch"
                + " size {}.",
            batch.ids().size(),
            target.chunkLabel(),
            nextBatchSize);
        batchSize = nextBatchSize;
        continue;
      }
      long batchCount = batch.ids().size();
      if (batchCount == 0) {
        throw new ProcessingException(
            "Memgraph embeddings refresh made no progress for " + target.chunkLabel());
      }
      updateMetadata(settings, target, dimension, batch.ids());
      embedded += batchCount;
      stale -= batchCount;
    }
    return new EmbeddingRefreshResult(embedded, dimension);
  }

  private int embeddingDimension(EmbeddingSettings settings) {
    String cacheKey = settings.modelName() + ":" + settings.dimensions();
    return dimensionCache.computeIfAbsent(cacheKey, ignored -> queryDimension(settings));
  }

  private int queryDimension(EmbeddingSettings settings) {
    Map<String, Object> params = Map.of(Params.CONFIG, settings.modelConfiguration());
    return cypher.read(
        Cypher.CYPHER_CODE_EMBEDDING_MODEL_INFO,
        params,
        result -> {
          if (!result.hasNext()) {
            throw new ProcessingException("Memgraph embeddings.model_info returned no rows");
          }
          int dimension = result.next().get("info").get(Rag.DIMENSION).asInt(0);
          if (dimension < 1) {
            throw new ProcessingException(
                "Memgraph embeddings.model_info returned invalid dimension " + dimension);
          }
          return dimension;
        });
  }

  private void ensureVectorIndex(EmbeddingSettings settings, EmbeddingTarget target, int dim) {
    int capacity = vectorIndexCapacity(settings, countChunks(target));
    Optional<VectorIndexInfo> existing = vectorIndexInfo(settings.indexName());
    if (existing.isPresent()) {
      verifyVectorIndex(settings, dim, existing.get(), target);
      if (existing.get().capacity() >= capacity) {
        return;
      }
      recreateVectorIndex(
          settings.indexName(), buildCreateIndexCypher(settings, target), settings, dim, capacity);
      return;
    }
    createVectorIndex(buildCreateIndexCypher(settings, target), settings, dim, capacity);
  }

  private void recreateVectorIndex(
      String indexName,
      String createIndexCypher,
      EmbeddingSettings settings,
      int dimension,
      int capacity) {
    log.info("Recreating vector index '{}' with capacity {}", indexName, capacity);
    cypher.run("DROP VECTOR INDEX " + indexName, Map.of());
    createVectorIndex(createIndexCypher, settings, dimension, capacity);
  }

  private void createVectorIndex(
      String createIndexCypher, EmbeddingSettings settings, int dimension, int capacity) {
    Map<String, Object> config =
        Map.of(
            Rag.DIMENSION,
            dimension,
            Rag.CAPACITY,
            capacity,
            Rag.METRIC,
            settings.metric(),
            Rag.SCALAR_KIND,
            settings.scalarKind());
    cypher.run(createIndexCypher, Map.of(Params.CONFIG, config));
  }

  private long countChunks(EmbeddingTarget target) {
    return cypher.read(
        target.countChunksCypher(), Map.of(), result -> result.single().get(Params.COUNT).asLong());
  }

  private long countStale(EmbeddingSettings settings, EmbeddingTarget target, int dimension) {
    return cypher.read(
        target.countStaleCypher(),
        Map.of(Params.MODEL_NAME, settings.modelName(), Rag.DIMENSION, dimension),
        result -> result.single().get(Params.COUNT).asLong());
  }

  private long countDirty(EmbeddingTarget target) {
    return cypher.read(
        target.countDirtyCypher(), Map.of(), result -> result.single().get(Params.COUNT).asLong());
  }

  private EmbeddingBatchResult refreshBatch(
      EmbeddingSettings settings, EmbeddingTarget target, int dimension, int batchSize) {
    Map<String, Object> config = buildEmbeddingConfig(settings, target, batchSize);
    Map<String, Object> params =
        Map.of(
            Params.MODEL_NAME,
            settings.modelName(),
            Rag.DIMENSION,
            dimension,
            Params.LIMIT,
            batchSize,
            Params.CONFIG,
            config);
    return cypher.read(
        target.batchCypher(), params, result -> getEmbeddingBatchResult(dimension, result));
  }

  private @NonNull EmbeddingBatchResult getEmbeddingBatchResult(int dimension, Result result) {
    if (!result.hasNext()) {
      return new EmbeddingBatchResult(true, List.of());
    }
    var row = result.single();
    boolean success = row.get("success").asBoolean(false);
    int actualDimension = row.get(Rag.DIMENSION).asInt(0);
    if (success && actualDimension != dimension) {
      throw new ProcessingException(
          "Memgraph embeddings.node_sentence returned dimension "
              + actualDimension
              + " but expected "
              + dimension);
    }
    return new EmbeddingBatchResult(success, row.get(Params.IDS).asList(Value::asString));
  }

  private void updateMetadata(
      EmbeddingSettings settings, EmbeddingTarget target, int dimension, List<String> ids) {
    if (ids.isEmpty()) {
      return;
    }
    cypher.run(
        target.updateMetadataCypher(),
        Map.of(Params.IDS, ids, Params.MODEL_NAME, settings.modelName(), Rag.DIMENSION, dimension));
  }

  private ProcessingException embeddingFailure(EmbeddingTarget target, List<String> ids) {
    if (ids.isEmpty()) {
      return new ProcessingException(
          "Memgraph embeddings.node_sentence returned false and no "
              + target.chunkLabel()
              + " id was returned");
    }
    String id = ids.get(0);
    String detail =
        cypher.read(
            target.failureDetailCypher(),
            Map.of(Params.ID, id),
            result -> {
              if (!result.hasNext()) {
                return Params.ID_EQUALS + id;
              }
              var row = result.single();
              StringBuilder sb = new StringBuilder(Params.ID_EQUALS + id);
              if (target.includePathInFailureDetail()) {
                sb.append(", path=").append(row.get(Params.PATH).asString(Const.Symbols.EMPTY));
              }
              sb.append(Const.Symbols.COMMA_SOURCE)
                  .append(row.get(Params.SOURCE_LABEL).asString(Const.Symbols.EMPTY))
                  .append(Const.Symbols.SPACE)
                  .append(row.get(Params.SOURCE_ID).asString(Const.Symbols.EMPTY))
                  .append(Const.Symbols.COMMA_PREVIEW)
                  .append(row.get(Params.PREVIEW).asString(Const.Symbols.EMPTY).replace('\n', ' '));
              return sb.toString();
            });
    return new ProcessingException(
        "Memgraph embeddings.node_sentence returned false for single "
            + target.chunkLabel()
            + " "
            + detail);
  }

  private String buildCreateIndexCypher(EmbeddingSettings settings, EmbeddingTarget target) {
    validateCypherIdentifier(
        EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY, "embedding property name");
    return target
        .createVectorIndexTemplate()
        .replace(Params.INDEX_NAME_PLACEHOLDER, settings.indexName())
        .replace(
            Params.EMBEDDING_PROPERTY_PLACEHOLDER, EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY);
  }

  private Optional<VectorIndexInfo> vectorIndexInfo(String indexName) {
    return cypher.read(
        Cypher.CYPHER_SHOW_VECTOR_INDEX_INFO,
        Map.of(),
        result -> {
          while (result.hasNext()) {
            var row = result.next();
            if (indexName.equals(row.get("index_name").asString(Const.Symbols.EMPTY))) {
              return Optional.of(
                  new VectorIndexInfo(
                      row.get(Params.LABEL).asString(Const.Symbols.EMPTY),
                      row.get(Params.PROPERTY).asString(Const.Symbols.EMPTY),
                      row.get(Rag.DIMENSION).asInt(0),
                      row.get(Rag.CAPACITY).asInt(0),
                      row.get(Rag.METRIC).asString(Const.Symbols.EMPTY),
                      row.get(Rag.SCALAR_KIND).asString(Const.Symbols.EMPTY)));
            }
          }
          return Optional.empty();
        });
  }

  private static void verifyVectorIndex(
      EmbeddingSettings settings, int dimension, VectorIndexInfo index, EmbeddingTarget target) {
    if (!target.chunkLabel().equals(index.label())
        || !EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY.equals(index.property())
        || dimension != index.dimension()
        || !settings.metric().equals(index.metric())
        || (!index.scalarKind().isBlank() && !settings.scalarKind().equals(index.scalarKind()))) {
      throw new ProcessingException(
          "Vector index '"
              + settings.indexName()
              + "' exists but is not compatible with requested "
              + target.chunkLabel()
              + " embeddings");
    }
  }

  private static int vectorIndexCapacity(EmbeddingSettings settings, long chunkCount) {
    return settings.capacity() > 0
        ? settings.capacity()
        : GraphWriter.defaultVectorIndexCapacity(chunkCount);
  }

  private static Map<String, Object> buildEmbeddingConfig(
      EmbeddingSettings settings, EmbeddingTarget target, int batchSize) {
    Map<String, Object> config = new HashMap<>(target.nodeConfigExtractor().apply(settings));
    config.put(Rag.BATCH_SIZE, batchSize);
    config.put(Rag.CHUNK_SIZE, Math.min(settings.chunkSize(), batchSize));
    return config;
  }

  static void validateCypherIdentifier(String value, String name) {
    if (!CYPHER_IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a Cypher identifier");
    }
  }
}
