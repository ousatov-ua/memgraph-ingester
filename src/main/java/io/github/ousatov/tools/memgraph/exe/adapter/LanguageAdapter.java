package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses source files for one supported language and writes their structure through {@link
 * GraphWriter}.
 *
 * @author Oleksii Usatov
 */
public interface LanguageAdapter {

  SourceLanguage language();

  boolean accepts(Path file);

  boolean ingestFile(GraphWriter writer, Path file);

  /** Returns an equivalent adapter configured for {@code sourceRoot}. */
  default LanguageAdapter forSourceRoot(Path sourceRoot) {
    return this;
  }

  default boolean shouldVisitDirectory(Path directory) {
    Path fileName = directory.getFileName();
    return fileName == null || !"node_modules".equals(fileName.toString());
  }

  default List<Path> discoverFiles(Path sourceRoot) {
    List<Path> files = new ArrayList<>();
    try {
      Files.walkFileTree(
          sourceRoot,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              return shouldVisitDirectory(dir)
                  ? FileVisitResult.CONTINUE
                  : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (attrs.isRegularFile() && accepts(file)) {
                files.add(file);
              }
              return FileVisitResult.CONTINUE;
            }
          });
      files.sort(Comparator.naturalOrder());
      return List.copyOf(files);
    } catch (IOException e) {
      throw new ProcessingException("Cannot walk source root", e);
    }
  }

  default String displayName() {
    return language().nodeName();
  }
}
