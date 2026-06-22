package io.github.ousatov.tools.memgraph.exe.metrics;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.vo.metrics.CypherTiming;
import io.github.ousatov.tools.memgraph.vo.metrics.CypherTimingSnapshot;
import io.github.ousatov.tools.memgraph.vo.metrics.IngestionPerformanceRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe counters for one ingestion run.
 *
 * @author Oleksii Usatov
 */
public final class IngestionRunStats {

  public static final String PHASE_PARSE = "phase.parse.ms";
  public static final String PHASE_PRELOAD = "phase.preload.ms";
  public static final String PHASE_CLEANUP = "phase.cleanup.ms";
  public static final String PHASE_WRITE = "phase.write.ms";
  public static final String PHASE_FINALIZE = "phase.finalize.ms";
  public static final String PHASE_EMBEDDING = "phase.embedding.ms";

  private static final int TOP_CYPHER_LIMIT =
      AppConfig.intValue("metrics.run-stats.top-cypher-limit");
  private static final int CYPHER_PREVIEW_LIMIT =
      AppConfig.intValue("metrics.run-stats.cypher-preview-limit");
  private static final String EMPTY_CYPHER_PREVIEW = "<empty>";
  private static final List<String> PHASE_ROWS =
      List.of(
          PHASE_PRELOAD, PHASE_PARSE, PHASE_CLEANUP, PHASE_WRITE, PHASE_FINALIZE, PHASE_EMBEDDING);

  private final LongAdder cypherStatements = new LongAdder();
  private final LongAdder cypherRows = new LongAdder();
  private final LongAdder ingestedFiles = new LongAdder();
  private final LongAdder skippedFiles = new LongAdder();
  private final LongAdder failedFiles = new LongAdder();
  private final LongAdder fileTransactions = new LongAdder();
  private final LongAdder fileTransactionFiles = new LongAdder();
  private final LongAdder writerWaitNanos = new LongAdder();
  private final LongAdder writerServiceNanos = new LongAdder();
  private final AtomicLong writerQueueMaxDepth = new AtomicLong();
  private final ConcurrentMap<String, LongAdder> phaseNanos = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, LongAdder> analyzerParseNanos = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, LongAdder> analyzerPreparationNanos =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, LongAdder> embeddingBatches = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, LongAdder> embeddingRows = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, CypherTiming> cypherTimings = new ConcurrentHashMap<>();
  private final Set<String> changedCallerSignatures = ConcurrentHashMap.newKeySet();
  private final Set<String> changedMethodNames = ConcurrentHashMap.newKeySet();
  private final Set<String> changedOwnerFqns = ConcurrentHashMap.newKeySet();
  private final int threads;
  private final long startedNanos;
  private volatile int totalFiles;
  private final AtomicInteger writerQueueCapacity = new AtomicInteger();

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

  /** Records one timed Cypher statement that affects one logical row. */
  public void recordCypherStatement(String cypher, long elapsedNanos) {
    recordCypherStatement();
    recordCypherTiming(cypher, 1, elapsedNanos);
  }

  /** Records one batched Cypher statement and the number of logical rows it processed. */
  public void recordCypherBatch(int rows) {
    cypherStatements.increment();
    cypherRows.add(rows);
  }

  /** Records one timed batched Cypher statement and the number of logical rows it processed. */
  public void recordCypherBatch(String cypher, int rows, long elapsedNanos) {
    recordCypherBatch(rows);
    recordCypherTiming(cypher, rows, elapsedNanos);
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

  /** Records one committed file transaction and the number of files it wrote. */
  public void recordFileTransaction(int files) {
    if (files <= 0) {
      return;
    }
    fileTransactions.increment();
    fileTransactionFiles.add(files);
  }

  /** Records time spent waiting for prepared files from parser workers. */
  public void recordWriterWaitNanos(long elapsedNanos) {
    if (elapsedNanos > 0) {
      writerWaitNanos.add(elapsedNanos);
    }
  }

  /** Records time spent by the serialized writer lane while applying prepared writes. */
  public void recordWriterServiceNanos(long elapsedNanos) {
    if (elapsedNanos > 0) {
      writerServiceNanos.add(elapsedNanos);
    }
  }

  /** Records writer queue capacity and latest observed depth. */
  public void recordWriterQueueDepth(int capacity, int depth) {
    writerQueueCapacity.set(Math.max(writerQueueCapacity.get(), capacity));
    writerQueueMaxDepth.accumulateAndGet(Math.max(0, depth), Math::max);
  }

  /** Adds elapsed time to a named ingestion phase. */
  public void recordPhaseNanos(String phase, long elapsedNanos) {
    if (elapsedNanos <= 0) {
      return;
    }
    phaseNanos.computeIfAbsent(phase, _ -> new LongAdder()).add(elapsedNanos);
  }

  /** Adds elapsed parser subprocess or in-process parser time for one language/adapter. */
  public void recordAnalyzerParseNanos(String language, long elapsedNanos) {
    recordNanos(analyzerParseNanos, language, elapsedNanos);
  }

  /** Adds elapsed adapter preparation time for one language/adapter. */
  public void recordAnalyzerPreparationNanos(String language, long elapsedNanos) {
    recordNanos(analyzerPreparationNanos, language, elapsedNanos);
  }

  /** Records embedding batches and rows refreshed for one chunk label. */
  public void recordEmbeddingRefresh(String chunkLabel, long batches, long rows) {
    String key = metricToken(chunkLabel);
    if (batches > 0) {
      embeddingBatches.computeIfAbsent(key, _ -> new LongAdder()).add(batches);
    }
    if (rows > 0) {
      embeddingRows.computeIfAbsent(key, _ -> new LongAdder()).add(rows);
    }
  }

  /** Records definitions changed by successful file writes for scoped post-processing. */
  public void recordChangedDefinitions(
      Collection<String> ownerFqns, Collection<String> callerSignatures) {
    addNonBlank(changedOwnerFqns, ownerFqns);
    addNonBlank(changedCallerSignatures, callerSignatures);
    callerSignatures.stream()
        .map(IngestionRunStats::methodNameFromSignature)
        .filter(name -> !name.isBlank())
        .forEach(changedMethodNames::add);
  }

  /** Records definitions from a {@link SourceFileDefinitions} after a successful file write. */
  public void recordChangedDefinitions(SourceFileDefinitions definitions) {
    List<String> ownerFqns = new ArrayList<>();
    ownerFqns.addAll(definitions.classFqns());
    ownerFqns.addAll(definitions.interfaceFqns());
    ownerFqns.addAll(definitions.annotationFqns());
    recordChangedDefinitions(ownerFqns, definitions.methodSignatures());
  }

  /** Records method names removed by cleanup for scoped pending-call retries. */
  public void recordDeletedMethodNames(Collection<String> methodNames) {
    addNonBlank(changedMethodNames, methodNames);
  }

  /** Returns changed caller signatures in stable order. */
  public List<String> changedCallerSignatures() {
    return changedCallerSignatures.stream().sorted().toList();
  }

  /** Returns changed method names in stable order. */
  public List<String> changedMethodNames() {
    return changedMethodNames.stream().sorted().toList();
  }

  /** Returns changed owner FQNs in stable order. */
  public List<String> changedOwnerFqns() {
    return changedOwnerFqns.stream().sorted().toList();
  }

  private static String methodNameFromSignature(String signature) {
    if (signature == null || signature.isBlank()) {
      return Const.Symbols.EMPTY;
    }
    int end = signature.indexOf('(');
    String prefix = end >= 0 ? signature.substring(0, end) : signature;
    int separator = prefix.lastIndexOf('.');
    return separator >= 0 ? prefix.substring(separator + 1) : prefix;
  }

  /** Builds an immutable metrics snapshot for the current counter values. */
  public IngestionPerformanceMetrics snapshot() {
    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    List<IngestionPerformanceRow> rows = new ArrayList<>();
    rows.add(row("files.total", totalFiles));
    rows.add(row("files.ingested", ingestedFiles.sum()));
    rows.add(row("files.skipped", skippedFiles.sum()));
    rows.add(row("files.failed", failedFiles.sum()));
    rows.add(row("threads", threads));
    rows.add(row("duration.ms", durationMs));
    rows.add(row("cypher.statements", cypherStatements.sum()));
    rows.add(row("cypher.rows", cypherRows.sum()));
    rows.add(
        row("cypher.rows_per_statement.avg", average(cypherRows.sum(), cypherStatements.sum())));
    rows.add(row("write.transactions", fileTransactions.sum()));
    rows.add(
        row(
            "write.files_per_transaction.avg",
            average(fileTransactionFiles.sum(), fileTransactions.sum())));
    rows.add(row("write.queue.capacity", writerQueueCapacity.get()));
    rows.add(row("write.queue.max_depth", writerQueueMaxDepth.get()));
    rows.add(row("write.queue.wait.ms", TimeUnit.NANOSECONDS.toMillis(writerWaitNanos.sum())));
    rows.add(row("write.service.ms", TimeUnit.NANOSECONDS.toMillis(writerServiceNanos.sum())));
    addPhaseRows(rows);
    addAnalyzerRows(rows);
    addEmbeddingRows(rows);
    addTopCypherRows(rows);
    return new IngestionPerformanceMetrics(rows);
  }

  private static IngestionPerformanceRow row(String name, long value) {
    return new IngestionPerformanceRow(name, String.valueOf(value));
  }

  private static long average(long numerator, long denominator) {
    return denominator == 0 ? 0 : numerator / denominator;
  }

  private void addPhaseRows(List<IngestionPerformanceRow> rows) {
    PHASE_ROWS.forEach(
        phase -> rows.add(row(phase, TimeUnit.NANOSECONDS.toMillis(sum(phaseNanos.get(phase))))));
  }

  private void addAnalyzerRows(List<IngestionPerformanceRow> rows) {
    analyzerPreparationNanos.keySet().stream()
        .sorted()
        .forEach(
            key ->
                rows.add(
                    row(
                        "analyzer." + key + ".prep.ms",
                        TimeUnit.NANOSECONDS.toMillis(sum(analyzerPreparationNanos.get(key))))));
    analyzerParseNanos.keySet().stream()
        .sorted()
        .forEach(
            key ->
                rows.add(
                    row(
                        "analyzer." + key + ".parse.ms",
                        TimeUnit.NANOSECONDS.toMillis(sum(analyzerParseNanos.get(key))))));
  }

  private void addEmbeddingRows(List<IngestionPerformanceRow> rows) {
    embeddingBatches.keySet().stream()
        .sorted()
        .forEach(
            key -> {
              rows.add(row("embedding." + key + ".batches", sum(embeddingBatches.get(key))));
              rows.add(row("embedding." + key + ".rows", sum(embeddingRows.get(key))));
            });
  }

  private void addTopCypherRows(List<IngestionPerformanceRow> rows) {
    List<CypherTimingSnapshot> snapshots =
        cypherTimings.entrySet().stream()
            .map(entry -> entry.getValue().snapshot(entry.getKey()))
            .sorted(
                Comparator.comparingLong(CypherTimingSnapshot::elapsedNanos)
                    .reversed()
                    .thenComparing(CypherTimingSnapshot::preview))
            .limit(TOP_CYPHER_LIMIT)
            .toList();
    for (int i = 0; i < snapshots.size(); i++) {
      rows.add(new IngestionPerformanceRow("cypher.top." + (i + 1), snapshots.get(i).value()));
    }
  }

  private static void addNonBlank(Set<String> target, Collection<String> values) {
    values.stream().filter(value -> value != null && !value.isBlank()).forEach(target::add);
  }

  private static long sum(LongAdder adder) {
    return adder == null ? 0L : adder.sum();
  }

  private static void recordNanos(
      ConcurrentMap<String, LongAdder> counters, String key, long elapsedNanos) {
    if (elapsedNanos <= 0) {
      return;
    }
    counters.computeIfAbsent(metricToken(key), _ -> new LongAdder()).add(elapsedNanos);
  }

  private static String metricToken(String value) {
    String normalized = value == null ? Const.Symbols.EMPTY : value.trim().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return "unknown";
    }
    StringBuilder out = new StringBuilder(normalized.length());
    boolean previousSeparator = false;
    for (int i = 0; i < normalized.length(); i++) {
      char ch = normalized.charAt(i);
      if (Character.isLetterOrDigit(ch)) {
        out.append(ch);
        previousSeparator = false;
      } else if (!previousSeparator) {
        out.append('_');
        previousSeparator = true;
      }
    }
    int length = out.length();
    if (length > 0 && out.charAt(length - 1) == '_') {
      out.deleteCharAt(length - 1);
    }
    return out.isEmpty() ? "unknown" : out.toString();
  }

  private void recordCypherTiming(String cypher, int rows, long elapsedNanos) {
    if (elapsedNanos <= 0) {
      return;
    }
    cypherTimings
        .computeIfAbsent(cypherPreview(cypher), _ -> new CypherTiming())
        .recordTime(rows, elapsedNanos);
  }

  private static String cypherPreview(String cypher) {
    int lineStart = 0;
    int length = cypher.length();
    while (lineStart < length) {
      int lineEnd = lineStart;
      while (lineEnd < length && !isLineSeparator(cypher.charAt(lineEnd))) {
        lineEnd++;
      }
      String preview = cypher.substring(lineStart, lineEnd).trim();
      if (!preview.isBlank()) {
        return abbreviate(preview);
      }
      lineStart = lineEnd + 1;
      while (lineStart < length && isLineSeparator(cypher.charAt(lineStart))) {
        lineStart++;
      }
    }
    return EMPTY_CYPHER_PREVIEW;
  }

  private static boolean isLineSeparator(char value) {
    return value == '\n' || value == '\r';
  }

  private static String abbreviate(String value) {
    if (value.length() <= CYPHER_PREVIEW_LIMIT) {
      return value;
    }
    return value.substring(0, CYPHER_PREVIEW_LIMIT - 3) + "...";
  }
}
