package io.github.ousatov.tools.memgraph.exe;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe counters for one ingestion run.
 *
 * @author Oleksii Usatov
 */
final class IngestionRunStats {

  private final LongAdder cypherStatements = new LongAdder();
  private final LongAdder cypherRows = new LongAdder();
  private final LongAdder ingestedFiles = new LongAdder();
  private final LongAdder skippedFiles = new LongAdder();
  private final LongAdder failedFiles = new LongAdder();
  private final int threads;
  private final long startedNanos;
  private volatile int totalFiles;

  IngestionRunStats(int threads) {
    this.threads = threads;
    this.startedNanos = System.nanoTime();
  }

  void setTotalFiles(int totalFiles) {
    this.totalFiles = totalFiles;
  }

  void recordCypherStatement() {
    cypherStatements.increment();
    cypherRows.increment();
  }

  void recordCypherBatch(int rows) {
    cypherStatements.increment();
    cypherRows.add(rows);
  }

  void recordIngestedFile() {
    ingestedFiles.increment();
  }

  void recordSkippedFile() {
    skippedFiles.increment();
  }

  void recordFailedFile() {
    failedFiles.increment();
  }

  IngestionPerformanceMetrics snapshot() {
    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    return new IngestionPerformanceMetrics(
        List.of(
            row("files.total", totalFiles),
            row("files.ingested", ingestedFiles.sum()),
            row("files.skipped", skippedFiles.sum()),
            row("files.failed", failedFiles.sum()),
            row("threads", threads),
            row("duration.ms", durationMs),
            row("cypher.statements", cypherStatements.sum()),
            row("cypher.rows", cypherRows.sum())));
  }

  private static IngestionPerformanceMetrics.Row row(String name, long value) {
    return new IngestionPerformanceMetrics.Row(name, String.valueOf(value));
  }
}
