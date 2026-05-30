package io.github.ousatov.tools.memgraph.vo.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Accumulated timing for one Cypher statement preview.
 *
 * @author Oleksii Usatov
 */
public final class CypherTiming {

  private final LongAdder calls = new LongAdder();
  private final LongAdder rows = new LongAdder();
  private final LongAdder elapsedNanos = new LongAdder();

  /** Records one timed call and the number of logical rows it processed. */
  public void recordTime(int rowCount, long elapsedNanos) {
    calls.increment();
    rows.add(rowCount);
    this.elapsedNanos.add(elapsedNanos);
  }

  /** Captures a stable timing snapshot for rendering. */
  public CypherTimingSnapshot snapshot(String preview) {
    return new CypherTimingSnapshot(preview, calls.sum(), rows.sum(), elapsedNanos.sum());
  }
}
