package io.github.ousatov.tools.memgraph.exe.metrics;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe counters for one ingestion run.
 *
 * @author Oleksii Usatov
 */
public final class IngestionRunStats {

  private final LongAdder cypherStatements = new LongAdder();
  private final LongAdder cypherRows = new LongAdder();
  private final LongAdder ingestedFiles = new LongAdder();
  private final LongAdder skippedFiles = new LongAdder();
  private final LongAdder failedFiles = new LongAdder();
  private final int threads;
  private final long startedNanos;
  private volatile int totalFiles;

  /** Creates counters for an ingestion run that uses {@code threads} worker threads. */
  public IngestionRunStats(int threads) {
    this.threads = threads;
    this.startedNanos = System.nanoTime();
  }

  /** Records the total number of source files discovered for the run. */
  public void setTotalFiles(int totalFiles) {
    this.totalFiles = totalFiles;
  }

  /** Records one Cypher statement that affects one logical row. */
  public void recordCypherStatement() {
    cypherStatements.increment();
    cypherRows.increment();
  }

  /** Records one batched Cypher statement and the number of logical rows it processed. */
  public void recordCypherBatch(int rows) {
    cypherStatements.increment();
    cypherRows.add(rows);
  }

  /** Records one successfully ingested source file. */
  public void recordIngestedFile() {
    ingestedFiles.increment();
  }

  /** Records one unchanged file skipped by incremental ingestion. */
  public void recordSkippedFile() {
    skippedFiles.increment();
  }

  /** Records one source file that failed parsing or graph writes. */
  public void recordFailedFile() {
    failedFiles.increment();
  }

  /** Builds an immutable metrics snapshot for the current counter values. */
  public IngestionPerformanceMetrics snapshot() {
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
