package io.github.ousatov.tools.memgraph.vo;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings for Memgraph-managed {@code :CodeChunk} and {@code :MemoryChunk} embedding refresh.
 *
 * @author Oleksii Usatov
 */
public record EmbeddingSettings(
    boolean enabled,
    String indexName,
    String modelName,
    String metric,
    String scalarKind,
    int batchSize,
    int chunkSize,
    String device,
    int dimensions,
    int remoteBatchSize,
    int concurrency,
    int procedureMemoryMb,
    int capacity,
    boolean required) {

  public static final String DEFAULT_CODE_INDEX_NAME =
      AppConfig.stringValue("embedding.code-index-name");
  public static final String DEFAULT_MEMORY_INDEX_NAME =
      AppConfig.stringValue("embedding.memory-index-name");
  public static final String DEFAULT_MODEL_NAME = AppConfig.stringValue("embedding.model-name");
  public static final String DEFAULT_EMBEDDING_PROPERTY =
      AppConfig.stringValue("embedding.property");
  public static final String DEFAULT_METRIC = AppConfig.stringValue("embedding.metric");
  public static final String DEFAULT_SCALAR_KIND = AppConfig.stringValue("embedding.scalar-kind");
  public static final int DEFAULT_BATCH_SIZE = AppConfig.intValue("embedding.batch-size");
  public static final int DEFAULT_CHUNK_SIZE = AppConfig.intValue("embedding.chunk-size");
  public static final int DEFAULT_DIMENSIONS = AppConfig.intValue("embedding.dimensions");
  public static final int DEFAULT_REMOTE_BATCH_SIZE =
      AppConfig.intValue("embedding.remote-batch-size");
  public static final int DEFAULT_CONCURRENCY = AppConfig.intValue("embedding.concurrency");
  public static final int DEFAULT_PROCEDURE_MEMORY_MB =
      AppConfig.intValue("embedding.procedure-memory-mb");
  public static final int DEFAULT_CAPACITY = AppConfig.intValue("embedding.capacity");

  /**
   * Every {@code :CodeChunk} property except {@code text}. The embeddings module concatenates all
   * non-excluded properties into the embedded string, so anything missing here leaks noise into the
   * model's limited input window.
   */
  private static final List<String> CODE_CHUNK_METADATA_PROPERTIES =
      List.of(
          Const.Params.ID,
          Const.Labels.PROJECT,
          Const.Params.SOURCE_LABEL,
          Const.Params.SOURCE_ID,
          "language",
          Const.Params.PATH,
          "ownerFqn",
          Const.Params.SIGNATURE,
          Const.Params.NAME,
          Const.Params.KIND,
          Const.Params.RAG_ROLE,
          Const.Params.START_LINE,
          Const.Params.END_LINE,
          Const.Params.IS_SYNTHETIC,
          Const.Params.EMBEDDING_DIRTY,
          Const.Params.TEXT_HASH,
          DEFAULT_EMBEDDING_PROPERTY,
          Const.Params.EMBEDDING_MODEL,
          Const.Params.EMBEDDING_DIMENSIONS,
          Const.Params.CREATED_AT,
          Const.Params.UPDATED_AT);

  private static final List<String> MEMORY_CHUNK_METADATA_PROPERTIES =
      List.of(
          Const.Params.ID,
          Const.Labels.PROJECT,
          Const.Params.SOURCE_LABEL,
          Const.Params.SOURCE_ID,
          Const.Params.TEXT_HASH,
          DEFAULT_EMBEDDING_PROPERTY,
          Const.Params.EMBEDDING_MODEL,
          Const.Params.EMBEDDING_DIMENSIONS,
          Const.Params.CREATED_AT,
          Const.Params.UPDATED_AT);

  public EmbeddingSettings(
      boolean enabled,
      String indexName,
      String modelName,
      String metric,
      String scalarKind,
      int batchSize,
      int chunkSize,
      String device,
      int dimensions,
      int remoteBatchSize,
      int concurrency,
      int capacity) {
    this(
        enabled,
        indexName,
        modelName,
        metric,
        scalarKind,
        batchSize,
        chunkSize,
        device,
        dimensions,
        remoteBatchSize,
        concurrency,
        DEFAULT_PROCEDURE_MEMORY_MB,
        capacity,
        false);
  }

  /** Normalizes defaults and validates numeric options. */
  public EmbeddingSettings {
    indexName = defaultIfBlank(indexName, DEFAULT_CODE_INDEX_NAME);
    modelName = defaultIfBlank(modelName, DEFAULT_MODEL_NAME);
    metric = defaultIfBlank(metric, DEFAULT_METRIC);
    scalarKind = defaultIfBlank(scalarKind, DEFAULT_SCALAR_KIND);
    device = device == null ? Const.Symbols.EMPTY : device.strip();
    batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;
    chunkSize = chunkSize == 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
    requirePositive(batchSize, "embedding batch size");
    requirePositive(chunkSize, "embedding chunk size");
    requireNonNegative(dimensions, "embedding dimensions");
    requireNonNegative(remoteBatchSize, "embedding remote batch size");
    requireNonNegative(concurrency, "embedding concurrency");
    requireNonNegative(procedureMemoryMb, "embedding procedure memory mb");
    requireNonNegative(capacity, "embedding index capacity");
  }

  /** Enabled defaults for {@code :CodeChunk} embeddings. */
  public static EmbeddingSettings codeDefaults() {
    return new EmbeddingSettings(
        true,
        DEFAULT_CODE_INDEX_NAME,
        null,
        null,
        null,
        0,
        0,
        Const.Symbols.EMPTY,
        DEFAULT_DIMENSIONS,
        DEFAULT_REMOTE_BATCH_SIZE,
        DEFAULT_CONCURRENCY,
        DEFAULT_PROCEDURE_MEMORY_MB,
        DEFAULT_CAPACITY,
        false);
  }

  /** Enabled defaults for {@code :MemoryChunk} embeddings. */
  public static EmbeddingSettings memoryDefaults() {
    return new EmbeddingSettings(
        true,
        DEFAULT_MEMORY_INDEX_NAME,
        null,
        null,
        null,
        0,
        0,
        Const.Symbols.EMPTY,
        DEFAULT_DIMENSIONS,
        DEFAULT_REMOTE_BATCH_SIZE,
        DEFAULT_CONCURRENCY,
        DEFAULT_PROCEDURE_MEMORY_MB,
        DEFAULT_CAPACITY,
        false);
  }

  /** Disabled default used by callers that opt out of embedding refresh. */
  public static EmbeddingSettings disabled() {
    return new EmbeddingSettings(
        false, null, null, null, null, 0, 0, Const.Symbols.EMPTY, 0, 0, 0, 0, 0, false);
  }

  public EmbeddingSettings withRequired(boolean required) {
    if (this.required == required) {
      return this;
    }
    return new EmbeddingSettings(
        enabled,
        indexName,
        modelName,
        metric,
        scalarKind,
        batchSize,
        chunkSize,
        device,
        dimensions,
        remoteBatchSize,
        concurrency,
        procedureMemoryMb,
        capacity,
        required);
  }

  /** Returns the Memgraph embeddings module configuration for {@code embeddings.node_sentence}. */
  public Map<String, Object> codeNodeSentenceConfiguration() {
    Map<String, Object> config = modelConfiguration();
    config.put(Const.Params.EMBEDDING_PROPERTY, DEFAULT_EMBEDDING_PROPERTY);
    config.put(Const.Params.EXCLUDED_PROPERTIES, CODE_CHUNK_METADATA_PROPERTIES);
    config.put(Const.Params.RETURN_EMBEDDINGS, false);
    return config;
  }

  /** Returns the Memgraph embeddings module configuration for {@code :MemoryChunk} nodes. */
  public Map<String, Object> memoryNodeSentenceConfiguration() {
    Map<String, Object> config = modelConfiguration();
    config.put(Const.Params.EMBEDDING_PROPERTY, DEFAULT_EMBEDDING_PROPERTY);
    config.put(Const.Params.EXCLUDED_PROPERTIES, MEMORY_CHUNK_METADATA_PROPERTIES);
    config.put(Const.Params.RETURN_EMBEDDINGS, false);
    return config;
  }

  /** Returns the Memgraph embeddings module configuration for {@code embeddings.model_info}. */
  public Map<String, Object> modelConfiguration() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("model_name", modelName);
    config.put("batch_size", batchSize);
    config.put("chunk_size", chunkSize);
    if (!device.isBlank()) {
      config.put("device", device);
    }
    if (dimensions > 0) {
      config.put("dimensions", dimensions);
    }
    if (remoteBatchSize > 0) {
      config.put("remote_batch_size", remoteBatchSize);
    }
    if (concurrency > 0) {
      config.put("concurrency", concurrency);
    }
    return config;
  }

  private static String defaultIfBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.strip();
  }

  private static void requirePositive(int value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(
          Const.Symbols.DOUBLE_DASH + name.replace(' ', '-') + " must be >= 1");
    }
  }

  private static void requireNonNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(
          Const.Symbols.DOUBLE_DASH + name.replace(' ', '-') + " must be >= 0");
    }
  }
}
