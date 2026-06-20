package io.github.ousatov.tools.memgraph.exe.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Session;
import org.neo4j.driver.async.AsyncSession;
import org.neo4j.driver.async.AsyncTransaction;
import org.neo4j.driver.async.ResultCursor;

/**
 * Unit tests for {@link GraphWriter}.
 *
 * @author Oleksii Usatov
 */
class GraphWriterTest {

  @Test
  void treatsMemgraphUniqueConstraintViolationAsRetryable() {
    RuntimeException error =
        new RuntimeException(
            "Unable to commit due to unique constraint violation on :Annotation(project, fqn)");

    assertTrue(GraphWriter.isRetryable(error));
  }

  @Test
  void treatsTransactionConflictsAsRetryableCaseInsensitively() {
    assertTrue(GraphWriter.isRetryable(new RuntimeException("SerializationError: retry later")));
    assertTrue(GraphWriter.isRetryable(new RuntimeException("deadlock detected")));
  }

  @Test
  void doesNotRetryUnrelatedErrors() {
    assertFalse(GraphWriter.isRetryable(new RuntimeException("syntax error in Cypher")));
  }

  @Test
  void defaultVectorIndexCapacityUsesHeadroomAndMinimum() {
    assertEquals(8192, GraphWriter.defaultVectorIndexCapacity(0));
    assertEquals(8192, GraphWriter.defaultVectorIndexCapacity(100));
    assertEquals(20_000, GraphWriter.defaultVectorIndexCapacity(10_000));
    assertEquals(Integer.MAX_VALUE, GraphWriter.defaultVectorIndexCapacity(Long.MAX_VALUE));
  }

  @Test
  void emptyStoredStateReadsReturnEmptyWithoutTouchingSession() {
    RecordingBolt bolt = new RecordingBolt();
    GraphWriter writer = new GraphWriter(bolt.session(), "project");

    assertEquals(Map.of(), writer.getAllFileLastModified(List.of(), SourceLanguage.JAVA));
    assertEquals(Set.of(), writer.getFilePathsMissingCodeChunks(List.of()));
  }

  @Test
  void deleteStaleDefinitionsBuffersCleanupUntilTransactionCommit() {
    RecordingBolt bolt = new RecordingBolt();
    GraphWriter writer =
        new GraphWriter(
            bolt.session(), bolt.asyncSession(), "project", new IngestionRunStats(1), "");

    writer.beginFileTransaction();
    writer.deleteStaleDefinitionsForFile(Path.of("A.java"), SourceFileDefinitions.empty());

    assertEquals(1, bolt.asyncBeginCalls);
    assertEquals(List.of(), bolt.asyncRunCyphers);

    writer.commitFileTransaction();

    assertEquals(
        List.of(
            Cypher.CYPHER_DELETE_STALE_DEFINITION_RELATIONS_FOR_FILE,
            Cypher.CYPHER_DELETE_STALE_DEFINITIONS_FOR_FILE),
        bolt.asyncRunCyphers);
    assertEquals(1, bolt.asyncCommitCalls);
    assertEquals(0, bolt.asyncRollbackCalls);
  }

  private static final class RecordingBolt {
    private final List<String> asyncRunCyphers = new ArrayList<>();
    private int asyncBeginCalls;
    private int asyncCommitCalls;
    private int asyncRollbackCalls;

    Session session() {
      return proxy(
          Session.class,
          (proxy, method, args) -> {
            if ("beginTransaction".equals(method.getName())) {
              throw new AssertionError("sync transaction should not be opened");
            }
            return defaultValue(proxy, method, args);
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
            return defaultValue(proxy, method, args);
          });
    }

    private AsyncTransaction asyncTransaction() {
      return proxy(
          AsyncTransaction.class,
          (proxy, method, args) ->
              switch (method.getName()) {
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
                default -> defaultValue(proxy, method, args);
              });
    }

    private ResultCursor resultCursor() {
      return proxy(
          ResultCursor.class,
          (proxy, method, args) -> {
            if ("consumeAsync".equals(method.getName())) {
              return CompletableFuture.completedFuture(null);
            }
            return defaultValue(proxy, method, args);
          });
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Object proxy, Method method, Object[] args) {
    return switch (method.getName()) {
      case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName() + " proxy";
      case "hashCode" -> System.identityHashCode(proxy);
      case "equals" -> args != null && args.length == 1 && proxy == args[0];
      default -> null;
    };
  }
}
