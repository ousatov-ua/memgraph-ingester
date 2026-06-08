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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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

  private static final Pattern CYPHER_IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
  private static final String NODE_SENTENCE_CALL = "CALL embeddings.node_sentence(chunks, $config)";
  private static final int MODEL_INFO_RETRIES = 6;
  private static final long MODEL_INFO_RETRY_DELAY_MS = 10_000;

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
   * @param dirtyOnly when {@code true}, refreshes dirty chunks first; required runs then backfill
   *     any remaining stale chunks so missing embeddings are not hidden by an unchanged write
   */
  EmbeddingRefreshResult refresh(
      EmbeddingSettings settings, EmbeddingTarget target, boolean dirtyOnly) {
    if (!settings.enabled()) {
      return new EmbeddingRefreshResult(0L, 0);
    }
    validateCypherIdentifier(settings.indexName(), target.indexNameParam());

    dropObsoleteVectorIndexes(settings, target);
    int dimension = embeddingDimension(settings);
    long clearedObsoleteEmbeddings = clearObsoleteChunkEmbeddings(settings, target, dimension);
    ensureVectorIndex(settings, target, dimension, clearedObsoleteEmbeddings > 0);
    if (settings.required()) {
      verifyEmbeddingReadiness(settings, target, dimension);
    }

    boolean useDirty = dirtyOnly && target.countDirtyCypher() != null;
    long markedStale = useDirty ? countDirty(target) : countStale(settings, target, dimension);
    long embedded = refreshMarkedChunks(settings, target, dimension, markedStale);
    if (useDirty && settings.required()) {
      long remainingStale = countStale(settings, target, dimension);
      embedded += refreshMarkedChunks(settings, target, dimension, remainingStale);
    }

    if (settings.required()) {
      verifyAllEmbeddingsCalculated(settings, target, dimension);
    }
    return new EmbeddingRefreshResult(embedded, dimension);
  }

  private long refreshMarkedChunks(
      EmbeddingSettings settings, EmbeddingTarget target, int dimension, long stale) {
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
    return embedded;
  }

  private int embeddingDimension(EmbeddingSettings settings) {
    String cacheKey = settings.modelName() + ":" + settings.dimensions();
    return dimensionCache.computeIfAbsent(cacheKey, ignored -> queryDimension(settings));
  }

  private int queryDimension(EmbeddingSettings settings) {
    Map<String, Object> params = Map.of(Params.CONFIG, settings.modelConfiguration());
    RuntimeException lastFailure = null;
    for (int attempt = 1; attempt <= MODEL_INFO_RETRIES; attempt++) {
      try {
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
      } catch (RuntimeException e) {
        lastFailure = e;
        log.warn(
            "embeddings.model_info attempt {}/{} failed for model '{}': {}. Retrying in {}s…",
            attempt,
            MODEL_INFO_RETRIES,
            settings.modelName(),
            e.getMessage(),
            MODEL_INFO_RETRY_DELAY_MS / 1000);
        try {
          TimeUnit.MILLISECONDS.sleep(MODEL_INFO_RETRY_DELAY_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new ProcessingException("Interrupted while waiting for embedding model", ie);
        }
      }
    }
    throw new ProcessingException(
        "embeddings.model_info failed after "
            + MODEL_INFO_RETRIES
            + " attempts for model '"
            + settings.modelName()
            + "'",
        lastFailure);
  }

  private void ensureVectorIndex(
      EmbeddingSettings settings, EmbeddingTarget target, int dim, boolean forceRecreate) {
    int capacity = vectorIndexCapacity(settings, countChunks(target));
    Optional<VectorIndexInfo> existing = vectorIndexInfo(settings.indexName());
    if (existing.isPresent()) {
      VectorIndexInfo index = existing.get();
      verifyVectorIndexIdentity(index, settings.indexName(), target);
      boolean needsRecreate =
          forceRecreate
              || index.dimension() != dim
              || !settings.metric().equals(index.metric())
              || (!index.scalarKind().isBlank()
                  && !settings.scalarKind().equals(index.scalarKind()))
              || index.capacity() < capacity;
      if (needsRecreate) {
        recreateVectorIndex(
            settings.indexName(),
            buildCreateIndexCypher(settings, target),
            settings,
            dim,
            capacity);
      }
    } else {
      createVectorIndex(buildCreateIndexCypher(settings, target), settings, dim, capacity);
    }
  }

  private void dropObsoleteVectorIndexes(EmbeddingSettings settings, EmbeddingTarget target) {
    List<String> toDropNames = obsoleteVectorIndexNames(settings, target);
    for (String name : toDropNames) {
      validateCypherIdentifier(name, "obsolete index name");
      log.info("Dropping obsolete vector index '{}'", name);
      cypher.run("DROP VECTOR INDEX " + name, Map.of());
    }
  }

  private List<String> obsoleteVectorIndexNames(
      EmbeddingSettings settings, EmbeddingTarget target) {
    return cypher.read(
        Cypher.CYPHER_SHOW_VECTOR_INDEX_INFO,
        Map.of(),
        result -> {
          List<String> obsolete = new ArrayList<>();
          while (result.hasNext()) {
            var row = result.next();
            String name = row.get("index_name").asString(Const.Symbols.EMPTY);
            String label = row.get(Params.LABEL).asString(Const.Symbols.EMPTY);
            String property = row.get(Params.PROPERTY).asString(Const.Symbols.EMPTY);
            if (!name.equals(settings.indexName())
                && target.chunkLabel().equals(label)
                && EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY.equals(property)) {
              obsolete.add(name);
            }
          }
          return obsolete;
        });
  }

  private long clearObsoleteChunkEmbeddings(
      EmbeddingSettings settings, EmbeddingTarget target, int dimension) {
    long cleared =
        cypher.read(
            target.clearObsoleteCypher(),
            Map.of(Params.MODEL_NAME, settings.modelName(), Rag.DIMENSION, dimension),
            result -> result.single().get(Params.COUNT).asLong());
    if (cleared > 0) {
      log.info(
          "Cleared {} obsolete {} embedding value(s) across all projects",
          cleared,
          target.chunkLabel());
    }
    return cleared;
  }

  private void verifyEmbeddingReadiness(
      EmbeddingSettings settings, EmbeddingTarget target, int dimension) {
    List<String> obsoleteIndexes = obsoleteVectorIndexNames(settings, target);
    if (!obsoleteIndexes.isEmpty()) {
      throw new ProcessingException(
          "Obsolete "
              + target.chunkLabel()
              + " vector index(es) still exist after cleanup: "
              + obsoleteIndexes);
    }
    VectorIndexInfo index =
        vectorIndexInfo(settings.indexName())
            .orElseThrow(
                () ->
                    new ProcessingException(
                        "Required vector index '"
                            + settings.indexName()
                            + "' was not created for "
                            + target.chunkLabel()
                            + " embeddings"));
    verifyVectorIndexIdentity(index, settings.indexName(), target);
    verifyVectorIndexConfiguration(settings, dimension, index, target);

    long obsoleteEmbeddings = countObsoleteChunkEmbeddings(settings, target, dimension);
    if (obsoleteEmbeddings > 0) {
      throw new ProcessingException(
          "Found "
              + obsoleteEmbeddings
              + " obsolete "
              + target.chunkLabel()
              + " embedding value(s) after cleanup");
    }
  }

  private long countObsoleteChunkEmbeddings(
      EmbeddingSettings settings, EmbeddingTarget target, int dimension) {
    return cypher.read(
        target.countObsoleteCypher(),
        Map.of(Params.MODEL_NAME, settings.modelName(), Rag.DIMENSION, dimension),
        result -> result.single().get(Params.COUNT).asLong());
  }

  private void verifyAllEmbeddingsCalculated(
      EmbeddingSettings settings, EmbeddingTarget target, int dimension) {
    long remaining = countStale(settings, target, dimension);
    if (remaining > 0) {
      throw new ProcessingException(
          "Required "
              + target.chunkLabel()
              + " embedding refresh left "
              + remaining
              + " stale chunk(s)");
    }
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
        batchCypher(settings, target),
        params,
        result -> getEmbeddingBatchResult(dimension, result));
  }

  static String batchCypher(EmbeddingSettings settings, EmbeddingTarget target) {
    if (settings.procedureMemoryMb() == 0) {
      return target.batchCypher();
    }
    String call =
        NODE_SENTENCE_CALL + " PROCEDURE MEMORY LIMIT " + settings.procedureMemoryMb() + " MB";
    String cypher = target.batchCypher().replace(NODE_SENTENCE_CALL, call);
    if (cypher.equals(target.batchCypher())) {
      throw new ProcessingException(
          "Embedding batch Cypher does not contain expected embeddings.node_sentence call");
    }
    return cypher;
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

  private static void verifyVectorIndexIdentity(
      VectorIndexInfo index, String indexName, EmbeddingTarget target) {
    if (!target.chunkLabel().equals(index.label())
        || !EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY.equals(index.property())) {
      throw new ProcessingException(
          "Vector index '"
              + indexName
              + "' targets label '"
              + index.label()
              + "' / property '"
              + index.property()
              + "' — cannot reuse it for "
              + target.chunkLabel()
              + " embeddings (expected label '"
              + target.chunkLabel()
              + "' / property '"
              + EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY
              + "')");
    }
  }

  private static void verifyVectorIndexConfiguration(
      EmbeddingSettings settings, int dimension, VectorIndexInfo index, EmbeddingTarget target) {
    if (dimension != index.dimension()
        || !settings.metric().equals(index.metric())
        || (!index.scalarKind().isBlank() && !settings.scalarKind().equals(index.scalarKind()))) {
      throw new ProcessingException(
          "Vector index '"
              + settings.indexName()
              + "' exists but does not match required "
              + target.chunkLabel()
              + " embedding config");
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
