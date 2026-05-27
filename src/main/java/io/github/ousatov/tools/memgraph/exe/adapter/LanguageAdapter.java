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
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Parses source files for one supported language and writes their structure through {@link
 * GraphWriter}.
 *
 * @author Oleksii Usatov
 */
public interface LanguageAdapter<T> {

  /** Returns the source language represented by this adapter. */
  SourceLanguage language();

  /** Returns a static source language when the adapter is bound to one graph language. */
  default Optional<SourceLanguage> staticLanguage() {
    return Optional.of(language());
  }

  /** Returns the source language selected for {@code file}. */
  default SourceLanguage language(Path file) {
    return language();
  }

  /** Returns true when this adapter can parse {@code file}. */
  boolean accepts(Path file);

  /** Returns true when this adapter could have parsed {@code file} before a delete event. */
  default boolean acceptsDeletedPath(Path file) {
    return accepts(file);
  }

  /** Parses {@code file} into an adapter-specific source model. */
  Optional<T> parse(Path file);

  /** Collects graph identities emitted when the parsed model is written. */
  SourceFileDefinitions collectDefinitions(T parsed);

  /** Writes the parsed source model to the graph. */
  boolean write(GraphWriter writer, Path file, T parsed);

  /** Returns an equivalent adapter configured for {@code sourceRoot}. */
  default LanguageAdapter<T> forSourceRoot(Path sourceRoot) {
    return this;
  }

  /** Returns true when discovery should descend into {@code directory}. */
  default boolean shouldVisitDirectory(Path directory) {
    Path fileName = directory.getFileName();
    return fileName == null || !"node_modules".equals(fileName.toString());
  }

  /** Returns {@code path} as a source-root-local path when both paths are compatible. */
  static Path localPath(Path sourceRoot, Path path) {
    try {
      return sourceRoot.relativize(path);
    } catch (IllegalArgumentException _) {
      return path;
    }
  }

  /** Returns true when this adapter provides its own discovery implementation. */
  default boolean usesCustomFileDiscovery() {
    return false;
  }

  /** Finds matching source files beneath {@code sourceRoot} in stable order. */
  default List<Path> discoverFiles(Path sourceRoot) {
    List<Path> files = new ArrayList<>();
    try {
      Files.walkFileTree(
          sourceRoot,
          new SimpleFileVisitor<>() {
            @Override
            public @NonNull FileVisitResult preVisitDirectory(
                @NonNull Path dir, @NonNull BasicFileAttributes attrs) {
              return shouldVisitDirectory(localPath(sourceRoot, dir))
                  ? FileVisitResult.CONTINUE
                  : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public @NonNull FileVisitResult visitFile(
                @NonNull Path file, @NonNull BasicFileAttributes attrs) {
              if (attrs.isRegularFile() && accepts(localPath(sourceRoot, file))) {
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

  /** Returns the human-readable adapter name used in logs. */
  default String displayName() {
    return language().nodeName();
  }
}
