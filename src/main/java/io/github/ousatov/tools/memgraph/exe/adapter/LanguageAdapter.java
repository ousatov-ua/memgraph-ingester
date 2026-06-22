package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Parses source files for one supported language and writes their structure through {@link
 * GraphWriter}.
 *
 * @author Oleksii Usatov
 */
public interface LanguageAdapter<T> {

  Set<String> COMMON_SKIPPED_DIRECTORIES =
      Set.of(
          ".git",
          ".hg",
          ".svn",
          Const.Files.NODE_MODULES,
          Const.Files.PYCACHE,
          Const.Files.VIRTUAL_ENV,
          Const.Files.UV_CACHE,
          Const.Files.VENV,
          Const.Files.TOX,
          Const.Files.NOX,
          Const.Files.SITE_PACKAGES,
          Const.Files.BUILD,
          Const.Files.DIST,
          Const.Files.TARGET,
          Const.Files.OUT);

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

  /** Parses files as one adapter-level batch, preserving one result per input file. */
  default List<ParseResult<T>> parseBatch(List<Path> files) {
    List<ParseResult<T>> results = new ArrayList<>(files.size());
    for (Path file : files) {
      try {
        Optional<T> parsed = parse(file);
        results.add(
            parsed
                .map(value -> ParseResult.parsed(file, value))
                .orElseGet(() -> ParseResult.empty(file)));
      } catch (RuntimeException e) {
        results.add(ParseResult.failed(file, e));
      }
    }
    return List.copyOf(results);
  }

  /** Returns true when {@link #parseBatch(List)} does real adapter-level batching. */
  default boolean supportsBatchParsing() {
    return false;
  }

  /** Prepares shared parser resources before parallel parsing starts. */
  default void prepare() {
    // Most adapters have no external parser runtime to prepare.
  }

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
    return shouldVisitSourceDirectory(directory);
  }

  /** Returns true when language discovery should visit a source-root-local directory. */
  static boolean shouldVisitSourceDirectory(Path directory) {
    Path fileName = directory.getFileName();
    return fileName == null || !COMMON_SKIPPED_DIRECTORIES.contains(fileName.toString());
  }

  /** Returns true when a source-root-local path sits under a common generated/dependency dir. */
  static boolean isInSkippedSourceDirectory(Path path) {
    for (Path part : path) {
      if (COMMON_SKIPPED_DIRECTORIES.contains(part.toString())) {
        return true;
      }
    }
    return false;
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

  /** Per-file result emitted by {@link #parseBatch(List)}. */
  record ParseResult<T>(Path file, Optional<T> parsed, RuntimeException failure) {

    public ParseResult {
      Objects.requireNonNull(file, "file");
      parsed = parsed == null ? Optional.empty() : parsed;
      if (parsed.isPresent() && failure != null) {
        throw new IllegalArgumentException("A parse result cannot be both parsed and failed");
      }
    }

    public static <T> ParseResult<T> parsed(Path file, T value) {
      return new ParseResult<>(file, Optional.of(Objects.requireNonNull(value, "value")), null);
    }

    public static <T> ParseResult<T> empty(Path file) {
      return new ParseResult<>(file, Optional.empty(), null);
    }

    public static <T> ParseResult<T> failed(Path file, RuntimeException failure) {
      return new ParseResult<>(file, Optional.empty(), Objects.requireNonNull(failure, "failure"));
    }
  }
}
