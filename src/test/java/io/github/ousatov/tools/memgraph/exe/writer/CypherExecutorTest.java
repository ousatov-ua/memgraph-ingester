package io.github.ousatov.tools.memgraph.exe.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.async.AsyncSession;
import org.neo4j.driver.async.AsyncTransaction;
import org.neo4j.driver.async.ResultCursor;

/**
 * Unit tests for {@link CypherExecutor}.
 *
 * @author Oleksii Usatov
 */
class CypherExecutorTest {

  @Test
  void asyncTransactionBuffersWritesUntilCommitWithoutOpeningSyncTransaction() {
    RecordingBolt bolt = new RecordingBolt();
    CypherExecutor executor =
        new CypherExecutor(
            bolt.session(), bolt.asyncSession(), "project", new IngestionRunStats(1));

    executor.beginTransaction();
    executor.run("CREATE (:A {project: $project})", Map.of());
    executor.runBatch("UNWIND $rows AS row CREATE (:B {id: row.id, project: $project})", rows());

    assertEquals(0, bolt.syncBeginCalls);
    assertEquals(0, bolt.asyncRunCyphers.size());

    executor.commitTransaction();

    assertEquals(1, bolt.asyncBeginCalls);
    assertEquals(
        List.of(
            "CREATE (:A {project: $project})",
            "UNWIND $rows AS row CREATE (:B {id: row.id, project: $project})"),
        bolt.asyncRunCyphers);
    assertEquals(1, bolt.asyncCommitCalls);
    assertEquals(0, bolt.asyncRollbackCalls);
  }

  @Test
  void asyncTransactionRollsBackWhenPipelineConsumeFails() {
    RecordingBolt bolt = new RecordingBolt();
    bolt.consumeFailure = new RuntimeException("failed consume");
    CypherExecutor executor =
        new CypherExecutor(
            bolt.session(), bolt.asyncSession(), "project", new IngestionRunStats(1));

    executor.beginTransaction();
    executor.run("CREATE (:A {project: $project})", Map.of());

    assertThrows(RuntimeException.class, executor::commitTransaction);

    assertEquals(1, bolt.asyncRunCyphers.size());
    assertEquals(0, bolt.asyncCommitCalls);
    assertEquals(1, bolt.asyncRollbackCalls);
  }

  private static List<Map<String, Object>> rows() {
    return List.of(Map.of("id", 1), Map.of("id", 2));
  }

  private static final class RecordingBolt {
    private final List<String> asyncRunCyphers = new ArrayList<>();
    private int syncBeginCalls;
    private int asyncBeginCalls;
    private int asyncCommitCalls;
    private int asyncRollbackCalls;
    private RuntimeException consumeFailure;

    Session session() {
      return proxy(
          Session.class,
          (proxy, method, args) -> {
            if ("beginTransaction".equals(method.getName())) {
              syncBeginCalls++;
              throw new AssertionError("sync transaction should not be opened");
            }
            return defaultValue(proxy, method);
          });
    }

    AsyncSession asyncSession() {
      return proxy(
          AsyncSession.class,
          (proxy, method, args) -> {
            if ("beginTransactionAsync".equals(method.getName())) {
              asyncBeginCalls++;
              return CompletableFuture.completedFuture(asyncTransaction());
            }
            return defaultValue(proxy, method);
          });
    }

    private AsyncTransaction asyncTransaction() {
      return proxy(
          AsyncTransaction.class,
          (proxy, method, args) -> {
            return switch (method.getName()) {
              case "runAsync" -> {
                asyncRunCyphers.add((String) args[0]);
                yield CompletableFuture.completedFuture(resultCursor());
              }
              case "commitAsync" -> {
                asyncCommitCalls++;
                yield CompletableFuture.completedFuture(null);
              }
              case "rollbackAsync" -> {
                asyncRollbackCalls++;
                yield CompletableFuture.completedFuture(null);
              }
              default -> defaultValue(proxy, method);
            };
          });
    }

    private ResultCursor resultCursor() {
      return proxy(
          ResultCursor.class,
          (proxy, method, args) -> {
            if ("consumeAsync".equals(method.getName())) {
              if (consumeFailure != null) {
                return CompletableFuture.failedFuture(consumeFailure);
              }
              return CompletableFuture.completedFuture(null);
            }
            return defaultValue(proxy, method);
          });
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Object proxy, Method method) {
    return switch (method.getName()) {
      case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName() + " proxy";
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> proxy == method;
      default -> null;
    };
  }
}
