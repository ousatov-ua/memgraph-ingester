package io.github.ousatov.tools.memgraph.vo;

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
    int capacity) {

  public static final String DEFAULT_CODE_INDEX_NAME = "code_chunk_embedding_v1";
  public static final String DEFAULT_MEMORY_INDEX_NAME = "memory_chunk_embedding_v1";
  public static final String DEFAULT_MODEL_NAME = "all-MiniLM-L6-v2";
  public static final String DEFAULT_EMBEDDING_PROPERTY = "embedding";
  public static final String DEFAULT_METRIC = "cos";
  public static final String DEFAULT_SCALAR_KIND = "f16";
  public static final int DEFAULT_BATCH_SIZE = 512;
  public static final int DEFAULT_CHUNK_SIZE = 48;

  private static final List<String> CODE_CHUNK_METADATA_PROPERTIES =
      List.of(
          "id",
          "project",
          "sourceLabel",
          "sourceId",
          "language",
          "path",
          "ownerFqn",
          "signature",
          "textHash",
          DEFAULT_EMBEDDING_PROPERTY,
          "embeddingModel",
          "embeddingDimensions",
          "createdAt",
          "updatedAt");

  private static final List<String> MEMORY_CHUNK_METADATA_PROPERTIES =
      List.of(
          "id",
          "project",
          "sourceLabel",
          "sourceId",
          "textHash",
          DEFAULT_EMBEDDING_PROPERTY,
          "embeddingModel",
          "embeddingDimensions",
          "createdAt",
          "updatedAt");

  /** Normalizes defaults and validates numeric options. */
  public EmbeddingSettings {
    indexName = defaultIfBlank(indexName, DEFAULT_CODE_INDEX_NAME);
    modelName = defaultIfBlank(modelName, DEFAULT_MODEL_NAME);
    metric = defaultIfBlank(metric, DEFAULT_METRIC);
    scalarKind = defaultIfBlank(scalarKind, DEFAULT_SCALAR_KIND);
    device = device == null ? "" : device.strip();
    batchSize = batchSize == 0 ? DEFAULT_BATCH_SIZE : batchSize;
    chunkSize = chunkSize == 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
    requirePositive(batchSize, "embedding batch size");
    requirePositive(chunkSize, "embedding chunk size");
    requireNonNegative(dimensions, "embedding dimensions");
    requireNonNegative(remoteBatchSize, "embedding remote batch size");
    requireNonNegative(concurrency, "embedding concurrency");
    requireNonNegative(capacity, "embedding index capacity");
  }

  /** Enabled defaults for {@code :CodeChunk} embeddings. */
  public static EmbeddingSettings codeDefaults() {
    return new EmbeddingSettings(
        true, DEFAULT_CODE_INDEX_NAME, null, null, null, 0, 0, "", 0, 0, 0, 0);
  }

  /** Enabled defaults for {@code :MemoryChunk} embeddings. */
  public static EmbeddingSettings memoryDefaults() {
    return new EmbeddingSettings(
        true, DEFAULT_MEMORY_INDEX_NAME, null, null, null, 0, 0, "", 0, 0, 0, 0);
  }

  /** Disabled default used by callers that opt out of embedding refresh. */
  public static EmbeddingSettings disabled() {
    return new EmbeddingSettings(false, null, null, null, null, 0, 0, "", 0, 0, 0, 0);
  }

  /** Returns the Memgraph embeddings module configuration for {@code embeddings.node_sentence}. */
  public Map<String, Object> codeNodeSentenceConfiguration() {
    Map<String, Object> config = modelConfiguration();
    config.put("embedding_property", DEFAULT_EMBEDDING_PROPERTY);
    config.put("excluded_properties", CODE_CHUNK_METADATA_PROPERTIES);
    config.put("return_embeddings", false);
    return config;
  }

  /** Returns the Memgraph embeddings module configuration for {@code :MemoryChunk} nodes. */
  public Map<String, Object> memoryNodeSentenceConfiguration() {
    Map<String, Object> config = modelConfiguration();
    config.put("embedding_property", DEFAULT_EMBEDDING_PROPERTY);
    config.put("excluded_properties", MEMORY_CHUNK_METADATA_PROPERTIES);
    config.put("return_embeddings", false);
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
      throw new IllegalArgumentException("--" + name.replace(' ', '-') + " must be >= 1");
    }
  }

  private static void requireNonNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException("--" + name.replace(' ', '-') + " must be >= 0");
    }
  }
}
