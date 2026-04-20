package io.github.ousatov.tools.memgraph.exception;

/**
 * ProcessingException
 *
 * @author Oleksii Usatov
 * @since 20.04.2026
 */
public final class ProcessingException extends RuntimeException {

  public ProcessingException(String message) {
    super(message);
  }

  public ProcessingException(Throwable cause) {
    super(cause);
  }

  public ProcessingException(String message, Throwable cause) {
    super(message, cause);
  }
}
