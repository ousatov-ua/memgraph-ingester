package io.github.ousatov.tools.memgraph.cli;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import picocli.CommandLine.Option;

/**
 * Picocli {@code @ArgGroup} binding for code-chunk embedding options.
 *
 * @author Oleksii Usatov
 */
public final class CodeEmbeddingCliOptions {

  @Option(
      names = {"--code-embeddings"},
      defaultValue = Const.Params.TRUE,
      // fallbackValue + arity stop picocli from toggling a default-true negatable flag to false.
      fallbackValue = Const.Params.TRUE,
      arity = "0..1",
      negatable = true,
      description =
          "Use Memgraph's embeddings module for stale :CodeChunk embeddings after"
              + " ingestion and watch updates.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean enabled = true;

  @Option(
      names = {"--code-embedding-device"},
      defaultValue = Const.Symbols.EMPTY,
      description =
          "Memgraph embeddings device. Leave blank for Memgraph auto-selection, or use cpu,"
              + " cuda, all, cuda:0, etc.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String device = Const.Symbols.EMPTY;

  @Option(
      names = {"--code-embedding-batch-size"},
      description =
          "Chunk nodes per Memgraph embedding call and local embedding batch size for CodeChunk"
              + " refresh.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int batchSize = EmbeddingSettings.DEFAULT_BATCH_SIZE;

  @Option(
      names = {"--code-embedding-chunk-size"},
      description = "Memgraph embeddings chunk_size for local multi-GPU computation.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int chunkSize = EmbeddingSettings.DEFAULT_CHUNK_SIZE;

  @Option(
      names = {"--code-embedding-remote-batch-size"},
      description = "Optional Memgraph remote_batch_size; 0 keeps the embeddings module default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int remoteBatchSize = EmbeddingSettings.DEFAULT_REMOTE_BATCH_SIZE;

  @Option(
      names = {"--code-embedding-concurrency"},
      description = "Optional Memgraph remote provider concurrency; 0 keeps the module default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int concurrency = EmbeddingSettings.DEFAULT_CONCURRENCY;

  @Option(
      names = {"--code-embedding-procedure-memory-mb"},
      description =
          "Optional Memgraph PROCEDURE MEMORY LIMIT for CodeChunk embedding calls in MB; 0 keeps"
              + " Memgraph's default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int procedureMemoryMb = EmbeddingSettings.DEFAULT_PROCEDURE_MEMORY_MB;

  @Option(
      names = {"--code-embedding-index-capacity"},
      description =
          "Optional vector index capacity; 0 uses the current CodeChunk count. The index uses "
              + "cosine metric and f16 scalar storage by default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int capacity = EmbeddingSettings.DEFAULT_CAPACITY;

  public EmbeddingSettings toSettings(String modelName, boolean required) {
    return new EmbeddingSettings(
        enabled,
        EmbeddingSettings.DEFAULT_CODE_INDEX_NAME,
        modelName,
        EmbeddingSettings.DEFAULT_METRIC,
        EmbeddingSettings.DEFAULT_SCALAR_KIND,
        batchSize,
        chunkSize,
        device,
        0,
        remoteBatchSize,
        concurrency,
        procedureMemoryMb,
        capacity,
        required && enabled);
  }

  public EmbeddingSettings toSettings(boolean required) {
    return toSettings(EmbeddingSettings.DEFAULT_MODEL_NAME, required);
  }
}
