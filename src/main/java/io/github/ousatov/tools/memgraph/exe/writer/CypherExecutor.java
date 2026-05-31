package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.neo4j.driver.QueryRunner;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes project-scoped Cypher statements with retry and optional transaction batching.
 *
 * @author Oleksii Usatov
 */
final class CypherExecutor {

  private static final Logger log = LoggerFactory.getLogger(CypherExecutor.class);

  private static final int MAX_RETRY_ATTEMPTS = 8;
  private static final long INITIAL_BACKOFF_MS = 10L;
  private static final long MAX_BACKOFF_MS = 500L;

  private final Session session;
  private final String project;
  private final IngestionRunStats stats;
  private Transaction currentTx;

  CypherExecutor(Session session, String project) {
    this(session, project, new IngestionRunStats(0));
  }

  CypherExecutor(Session session, String project, IngestionRunStats stats) {
    this.session = session;
    this.project = project;
    this.stats = stats;
  }

  /** Opens an explicit transaction for one file ingest. */
  void beginTransaction() {
    currentTx = session.beginTransaction();
  }

  /** Commits the active transaction, if one exists. */
  void commitTransaction() {
    if (currentTx != null) {
      currentTx.commit();
      currentTx = null;
    }
  }

  /** Rolls back the active transaction, ignoring rollback cleanup failures. */
  void rollbackTransaction() {
    if (currentTx != null) {
      try {
        currentTx.rollback();
      } catch (Exception e) {
        log.debug("Rollback failed: {}", e.getMessage());
      } finally {
        currentTx = null;
      }
    }
  }

  /** Runs a write statement with retry-on-conflict and automatic project parameter injection. */
  void run(String cypher, Map<String, Object> params) {
    Map<String, Object> allParams = paramsWithProject(params);
    executeWithRetry(
        cypher,
        () -> {
          long t = System.nanoTime();
          queryRunner().run(cypher, allParams).consume();
          stats.recordCypherStatement(cypher, System.nanoTime() - t);
        });
  }

  /** Runs one write statement for multiple homogeneous parameter rows. */
  void runBatch(String cypher, List<Map<String, Object>> rows) {
    if (rows.isEmpty()) {
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

  /** Runs a read query and maps its result, injecting the project parameter. */
  <T> T read(String cypher, Map<String, Object> params, Function<Result, T> mapper) {
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
    long nextBackoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
    log.debug("Conflict on attempt {}; will retry: {}", attempt, e.getMessage());
    return nextBackoffMs;
  }
}
