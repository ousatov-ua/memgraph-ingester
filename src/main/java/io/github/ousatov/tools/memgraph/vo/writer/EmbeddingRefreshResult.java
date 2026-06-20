package io.github.ousatov.tools.memgraph.vo.writer;

/**
 * Result counters for one embedding refresh pass.
 *
 * @author Oleksii Usatov
 */
public record EmbeddingRefreshResult(long embedded, int dimension, int batches) {

  public EmbeddingRefreshResult(long embedded, int dimension) {
    this(embedded, dimension, 0);
  }
}
