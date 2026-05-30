package io.github.ousatov.tools.memgraph.vo.metrics;

import java.util.concurrent.TimeUnit;

/**
 * Renderable Cypher timing snapshot.
 *
 * @author Oleksii Usatov
 */
public record CypherTimingSnapshot(String preview, long calls, long rows, long elapsedNanos) {

  /** Renders the timing value used in the metrics table. */
  public String value() {
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    return elapsedMs + " ms, calls=" + calls + ", rows=" + rows + ", " + preview;
  }
}
