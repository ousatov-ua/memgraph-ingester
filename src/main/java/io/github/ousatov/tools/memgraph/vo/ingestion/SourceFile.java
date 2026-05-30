package io.github.ousatov.tools.memgraph.vo.ingestion;

import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import java.nio.file.Path;

/**
 * Source file paired with the adapter selected by extension.
 *
 * @author Oleksii Usatov
 */
public record SourceFile(Path path, LanguageAdapter<?> adapter) {

  /** Returns the source language selected by the adapter for this file. */
  public SourceLanguage language() {
    return adapter.language(path);
  }
}
