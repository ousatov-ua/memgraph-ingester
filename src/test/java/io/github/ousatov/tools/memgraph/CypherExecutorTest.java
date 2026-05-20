package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CypherExecutor}.
 *
 * @author Oleksii Usatov
 */
class CypherExecutorTest {

  @Test
  void doesNotTreatNullMessageAsRetryable() {
    assertFalse(CypherExecutor.isRetryable(new RuntimeException((String) null)));
  }

  @Test
  void commitAndRollbackAreNoOpsWithoutActiveTransaction() {
    CypherExecutor executor = new CypherExecutor(null, "test");

    assertDoesNotThrow(executor::commitTransaction);
    assertDoesNotThrow(executor::rollbackTransaction);
  }
}
