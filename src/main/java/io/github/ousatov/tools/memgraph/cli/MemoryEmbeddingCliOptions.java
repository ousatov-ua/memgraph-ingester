package io.github.ousatov.tools.memgraph.cli;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import picocli.CommandLine.Option;

/**
 * Picocli {@code @ArgGroup} binding for memory-chunk embedding options.
 *
 * @author Oleksii Usatov
 */
public final class MemoryEmbeddingCliOptions {

  @Option(
      names = {"--memory-embeddings"},
      defaultValue = Const.Params.TRUE,
      negatable = true,
      description =
          "With --with-memories, sync :MemoryChunk rows and refresh stale embeddings after"
              + " ingestion and watch updates.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean enabled = true;

  @Option(
      names = {"--memory-embedding-device"},
      defaultValue = Const.Symbols.EMPTY,
      description =
          "Memgraph embeddings device for MemoryChunk refresh. Leave blank for Memgraph"
              + " auto-selection, or use cpu, cuda, all, cuda:0, etc.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String device = Const.Symbols.EMPTY;

  @Option(
      names = {"--memory-embedding-batch-size"},
      defaultValue = Const.Symbols.EMPTY + EmbeddingSettings.DEFAULT_BATCH_SIZE,
      description =
          "Chunk nodes per Memgraph embedding call and local embedding batch size for"
              + " MemoryChunk refresh.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int batchSize = EmbeddingSettings.DEFAULT_BATCH_SIZE;

  @Option(
      names = {"--memory-embedding-chunk-size"},
      defaultValue = Const.Symbols.EMPTY + EmbeddingSettings.DEFAULT_CHUNK_SIZE,
      description = "Memgraph embeddings chunk_size for local MemoryChunk computation.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int chunkSize = EmbeddingSettings.DEFAULT_CHUNK_SIZE;

  @Option(
      names = {"--memory-embedding-remote-batch-size"},
      defaultValue = Const.Params.ZERO,
      description =
          "Optional Memgraph remote_batch_size for MemoryChunk refresh; 0 keeps the embeddings"
              + " module default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int remoteBatchSize;

  @Option(
      names = {"--memory-embedding-concurrency"},
      defaultValue = Const.Params.ZERO,
      description =
          "Optional Memgraph remote provider concurrency for MemoryChunk refresh; 0 keeps the"
              + " module default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int concurrency;

  @Option(
      names = {"--memory-embedding-index-capacity"},
      defaultValue = Const.Params.ZERO,
      description =
          "Optional MemoryChunk vector index capacity; 0 uses the current MemoryChunk count."
              + " The index uses cosine metric and f16 scalar storage by default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public int capacity;

  public EmbeddingSettings toSettings(boolean memoryRequired) {
    return new EmbeddingSettings(
        memoryRequired && enabled,
        EmbeddingSettings.DEFAULT_MEMORY_INDEX_NAME,
        EmbeddingSettings.DEFAULT_MODEL_NAME,
        EmbeddingSettings.DEFAULT_METRIC,
        EmbeddingSettings.DEFAULT_SCALAR_KIND,
        batchSize,
        chunkSize,
        device,
        0,
        remoteBatchSize,
        concurrency,
        capacity);
  }
}
