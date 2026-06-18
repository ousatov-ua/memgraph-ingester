package io.github.ousatov.tools.memgraph.vo.writer;

/**
 * Receives incremental progress while stale chunk embeddings are refreshed in batches.
 *
 * <p>Implementations must tolerate being called from the thread that runs the embedding refresh and
 * should never throw, so a misbehaving progress UI cannot abort ingestion.
 *
 * @author Oleksii Usatov
 */
@FunctionalInterface
public interface EmbeddingProgressListener {

  /** No-op listener used when progress reporting is disabled. */
  EmbeddingProgressListener NONE = (embedded, total) -> {};

  /**
   * Reports embedding progress.
   *
   * @param embedded number of chunks embedded so far in this refresh pass
   * @param total total number of chunks to embed in this refresh pass
   */
  void onProgress(long embedded, long total);
}
