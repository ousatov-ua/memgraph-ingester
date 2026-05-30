package io.github.ousatov.tools.memgraph.vo.ingestion;

import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceFileDefinitions;
import java.nio.file.Path;

/**
 * Prepared source file that requires graph writes.
 *
 * @author Oleksii Usatov
 */
public record PreparedWrite<T>(
    Path path, LanguageAdapter<T> adapter, T parsed, SourceFileDefinitions definitions)
    implements PreparedFile {

  @Override
  public boolean success() {
    return true;
  }

  @Override
  public boolean writeRequired() {
    return true;
  }
}
