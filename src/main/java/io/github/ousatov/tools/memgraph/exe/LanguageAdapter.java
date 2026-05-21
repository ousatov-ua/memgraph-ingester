package io.github.ousatov.tools.memgraph.exe;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Parses source files for one language and writes their structure through {@link GraphWriter}. */
public interface LanguageAdapter {

  SourceLanguage language();

  boolean accepts(Path file);

  boolean ingestFile(GraphWriter writer, Path file);

  default List<Path> discoverFiles(Path sourceRoot) {
    try (Stream<Path> walk = Files.walk(sourceRoot)) {
      return walk.filter(Files::isRegularFile).filter(this::accepts).sorted().toList();
    } catch (IOException e) {
      throw new ProcessingException("Cannot walk source root", e);
    }
  }

  default String displayName() {
    return language().graphName();
  }
}
