package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.neo4j.driver.QueryRunner;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.async.AsyncSession;
import org.neo4j.driver.async.AsyncTransaction;
import org.neo4j.driver.async.ResultCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes project-scoped Cypher statements with retry and Bolt pipelining within transactions.
 *
 * <p>Within an explicit transaction ({@link #beginTransaction()}/{@link #commitTransaction()})
 * writes are buffered and dispatched via {@link AsyncTransaction#runAsync(String, Map)} so the
 * driver can pipeline them on the wire. Buffered writes are flushed and awaited at commit time and
 * discarded at rollback time. Writes outside an explicit transaction still execute synchronously
 * with exponential-backoff retries on transient failures.
 *
 * <p>Pipelining requires an {@link AsyncSession}; callers that do not pass one fall back to the
 * previous synchronous transaction behavior.
 *
 * @author Oleksii Usatov
 */
class CypherExecutor {

  private static final Logger log = LoggerFactory.getLogger(CypherExecutor.class);

  private static final int MAX_RETRY_ATTEMPTS =
      AppConfig.intValue("writer.cypher-retry.max-attempts");
  private static final long INITIAL_BACKOFF_MS =
      AppConfig.durationValue("writer.cypher-retry.initial-backoff").toMillis();
  private static final long MAX_BACKOFF_MS =
      AppConfig.durationValue("writer.cypher-retry.max-backoff").toMillis();

  private final Session session;
  private final AsyncSession asyncSession;
  private final String project;
  private final IngestionRunStats stats;
  private Transaction currentTx;
  private AsyncTransaction currentAsyncTx;
  private List<PendingOp> pendingOps = new ArrayList<>();

  CypherExecutor(
      Session session, AsyncSession asyncSession, String project, IngestionRunStats stats) {
    this.session = session;
    this.asyncSession = asyncSession;
    this.project = project;
    this.stats = stats;
  }

  /** Opens an explicit transaction for one file ingest. */
  void beginTransaction() {
    if (currentTx != null || currentAsyncTx != null) {
      throw new IllegalStateException("Transaction already active");
    }
    if (asyncSession != null) {
      currentAsyncTx = await(asyncSession.beginTransactionAsync());
    } else {
      currentTx = session.beginTransaction();
    }
    pendingOps = new ArrayList<>();
  }

  /** Commits the active transaction, if one exists. */
  void commitTransaction() {
    if (currentTx == null && currentAsyncTx == null) {
      return;
    }
    try {
      flushPendingOps();
      if (currentAsyncTx != null) {
        await(currentAsyncTx.commitAsync());
      } else {
        currentTx.commit();
      }
    } catch (RuntimeException e) {
      rollbackTransaction();
      throw e;
    } finally {
      clearTransaction();
    }
  }

  /** Rolls back the active transaction, ignoring rollback cleanup failures. */
  void rollbackTransaction() {
    if (currentTx == null && currentAsyncTx == null) {
      return;
    }
    try {
      if (currentAsyncTx != null) {
        try {
          await(currentAsyncTx.rollbackAsync());
        } catch (RuntimeException e) {
          log.debug("Rollback failed: {}", e.getMessage());
        }
      } else {
        try {
          currentTx.rollback();
        } catch (Exception e) {
          log.debug("Rollback failed: {}", e.getMessage());
        }
      }
    } finally {
      clearTransaction();
    }
  }

  /** Runs a write statement with retry-on-conflict and automatic project parameter injection. */
  void run(String cypher, Map<String, Object> params) {
    if (shouldBuffer()) {
      pendingOps.add(new PendingOp(cypher, paramsWithProject(params), OpKind.STATEMENT));
      return;
    }
    Map<String, Object> allParams = paramsWithProject(params);
    executeWithRetry(
        cypher,
        () -> {
          long t = System.nanoTime();
          queryRunner().run(cypher, allParams).consume();
          stats.recordCypherStatement(cypher, System.nanoTime() - t);
        });
  }

  /** Runs a write and consumes it before later buffered writes are sent. */
  void runAndFlush(String cypher, Map<String, Object> params) {
    run(cypher, params);
    if (shouldBuffer()) {
      flushPendingOps();
    }
  }

  /** Runs one write statement for multiple homogeneous parameter rows. */
  void runBatch(String cypher, List<Map<String, Object>> rows) {
    if (rows.isEmpty()) {
      return;
    }
    if (shouldBuffer()) {
      pendingOps.add(
          new PendingOp(cypher, paramsWithProject(Map.of(Const.Params.ROWS, rows)), OpKind.BATCH));
      return;
    }
    Map<String, Object> allParams = paramsWithProject(Map.of(Const.Params.ROWS, rows));
    executeWithRetry(
        cypher,
        () -> {
          long t = System.nanoTime();
          queryRunner().run(cypher, allParams).consume();
          stats.recordCypherBatch(cypher, rows.size(), System.nanoTime() - t);
        });
  }

  /** Runs a count query that returns a single numeric result column. */
  long runCount(String cypher, String extraKey, Object extraValue, String resultKey) {
    ensureNoAsyncTransaction("count query");
    Map<String, Object> allParams = paramsWithProject(Map.of(extraKey, extraValue));
    long[] result = {0L};
    executeWithRetry(
        cypher,
        () -> {
          long t = System.nanoTime();
          result[0] = session.run(cypher, allParams).single().get(resultKey).asLong();
          stats.recordCypherStatement(cypher, System.nanoTime() - t);
        });
    return result[0];
  }

  /**
   * Runs a read query and collects the {@code path} string column into a {@link Set} of {@link
   * Path} values, dropping null entries.
   */
  Set<Path> readPathSet(String cypher, Map<String, Object> params) {
    return read(
        cypher,
        params,
        result -> {
          Set<Path> paths = new HashSet<>();
          while (result.hasNext()) {
            String path = result.next().get(Params.PATH).asString(null);
            if (path != null) {
              paths.add(Path.of(path));
            }
          }
          return paths;
        });
  }

  /** Runs a read query and maps its result, injecting the project parameter. */
  <T> T read(String cypher, Map<String, Object> params, Function<Result, T> mapper) {
    ensureNoAsyncTransaction("read query");
    long startedNanos = System.nanoTime();
    T value = mapper.apply(session.run(cypher, paramsWithProject(params)));
    stats.recordCypherStatement(cypher, System.nanoTime() - startedNanos);
    return value;
  }

  static boolean isRetryable(RuntimeException e) {
    String msg =
        e.getMessage() == null ? Const.Symbols.EMPTY : e.getMessage().toLowerCase(Locale.ROOT);
    return msg.contains("conflicting transactions")
        || msg.contains("deadlock")
        || msg.contains("serializationerror")
        || msg.contains("cannot run more queries in this transaction")
        || msg.contains("fatal error")
        || msg.contains("explicitly terminated")
        || msg.contains("unique constraint violation");
  }

  private QueryRunner queryRunner() {
    return currentTx != null ? currentTx : session;
  }

  private boolean shouldBuffer() {
    return currentAsyncTx != null;
  }

  private void flushPendingOps() {
    if (pendingOps.isEmpty()) {
      return;
    }
    if (currentAsyncTx == null) {
      // Fallback: no async session — execute buffered writes synchronously inside the transaction.
      for (PendingOp op : pendingOps) {
        long t = System.nanoTime();
        currentTx.run(op.cypher, op.params).consume();
        long elapsed = System.nanoTime() - t;
        switch (op.kind) {
          case STATEMENT -> stats.recordCypherStatement(op.cypher, elapsed);
          case BATCH -> stats.recordCypherBatch(op.cypher, rowCountOf(op), elapsed);
        }
      }
      pendingOps.clear();
      return;
    }
    List<CompletableFuture<PendingResult>> stages = new ArrayList<>(pendingOps.size());
    for (PendingOp op : pendingOps) {
      long startedNanos = System.nanoTime();
      stages.add(
          currentAsyncTx
              .runAsync(op.cypher, op.params)
              .thenCompose(ResultCursor::consumeAsync)
              .thenApply(_ -> new PendingResult(op, System.nanoTime() - startedNanos))
              .toCompletableFuture());
    }
    try {
      CompletableFuture.allOf(stages.toArray(new CompletableFuture[0])).join();
    } catch (CompletionException ce) {
      throw rethrow(ce.getCause());
    }
    for (CompletableFuture<PendingResult> stage : stages) {
      PendingResult result = stage.join();
      switch (result.op.kind) {
        case STATEMENT -> stats.recordCypherStatement(result.op.cypher, result.elapsedNanos);
        case BATCH ->
            stats.recordCypherBatch(result.op.cypher, rowCountOf(result.op), result.elapsedNanos);
      }
    }
    pendingOps.clear();
  }

  private void ensureNoAsyncTransaction(String operation) {
    if (currentAsyncTx != null) {
      throw new IllegalStateException(
          "Cannot run " + operation + " inside an async write transaction");
    }
  }

  private static int rowCountOf(PendingOp op) {
    Object rows = op.params.get(Const.Params.ROWS);
    return rows instanceof List<?> list ? list.size() : 1;
  }

  private static RuntimeException rethrow(Throwable cause) {
    if (cause instanceof RuntimeException re) {
      return re;
    }
    if (cause instanceof Error err) {
      throw err;
    }
    if (cause == null) {
      return new RuntimeException("Unknown pipeline failure");
    }
    return new RuntimeException(cause);
  }

  private static <T> T await(CompletionStage<T> stage) {
    try {
      return stage.toCompletableFuture().join();
    } catch (CompletionException ce) {
      throw rethrow(ce.getCause());
    }
  }

  private void clearTransaction() {
    currentTx = null;
    currentAsyncTx = null;
    pendingOps = new ArrayList<>();
  }

  /**
   * Runs {@code action} once when inside a transaction (errors propagate immediately), or with
   * exponential back-off retries when outside a transaction.
   */
  private void executeWithRetry(String cypher, Runnable action) {
    if (currentTx != null) {
      action.run();
      return;
    }
    long backoffMs = INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
      try {
        action.run();
        return;
      } catch (RuntimeException e) {
        backoffMs = proceedException(cypher, e, attempt, backoffMs);
      }
    }
  }

  private Map<String, Object> paramsWithProject(Map<String, Object> params) {
    Map<String, Object> allParams = new HashMap<>(params);
    allParams.put(Labels.PROJECT, project);
    return allParams;
  }

  private long proceedException(String cypher, RuntimeException e, int attempt, long backoffMs) {
    if (!isRetryable(e)) {
      throw e;
    }
    if (attempt == MAX_RETRY_ATTEMPTS) {
      throw new ProcessingException(
          "Cypher failed after " + MAX_RETRY_ATTEMPTS + " attempts: " + cypher, e);
    }
    try {
      long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, backoffMs / 2));
      TimeUnit.MILLISECONDS.sleep(backoffMs + jitter);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted during retry", ie);
    }
    long nextBackoffMs = Math.clamp(backoffMs * 2, 0L, MAX_BACKOFF_MS);
    log.debug("Conflict on attempt {}; will retry: {}", attempt, e.getMessage());
    return nextBackoffMs;
  }

  private enum OpKind {
    STATEMENT,
    BATCH
  }

  private record PendingOp(String cypher, Map<String, Object> params, OpKind kind) {}

  private record PendingResult(PendingOp op, long elapsedNanos) {}
}
