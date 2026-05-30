package io.github.ousatov.tools.memgraph.vo.ingestion;

import java.nio.file.Path;

/**
 * Prepared source file that failed before graph writes.
 *
 * @author Oleksii Usatov
 */
public record PreparedFailure(Path path) implements PreparedFile {

  @Override
  public boolean success() {
    return false;
  }

  @Override
  public boolean writeRequired() {
    return false;
  }
}
