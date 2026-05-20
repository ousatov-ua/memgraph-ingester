package io.github.ousatov.tools.memgraph.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProcessingException}.
 *
 * @author Oleksii Usatov
 */
class ProcessingExceptionTest {

  @Test
  void preservesMessage() {
    ProcessingException error = new ProcessingException("failed");

    assertEquals("failed", error.getMessage());
  }

  @Test
  void preservesCause() {
    RuntimeException cause = new RuntimeException("root");
    ProcessingException error = new ProcessingException(cause);

    assertSame(cause, error.getCause());
  }

  @Test
  void preservesMessageAndCause() {
    RuntimeException cause = new RuntimeException("root");
    ProcessingException error = new ProcessingException("failed", cause);

    assertEquals("failed", error.getMessage());
    assertSame(cause, error.getCause());
  }
}
