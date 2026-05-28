package io.github.ousatov.tools.memgraph.exe.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
}
