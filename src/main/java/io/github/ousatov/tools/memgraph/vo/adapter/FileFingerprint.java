package io.github.ousatov.tools.memgraph.vo.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

/**
 * Stable file attributes used to validate cached source-language detection.
 *
 * @author Oleksii Usatov
 */
public record FileFingerprint(FileTime lastModifiedTime, long size) {

  /** Reads a fingerprint for an existing regular file. */
  public static Optional<FileFingerprint> read(Path file) {
    try {
      BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
      return Optional.of(new FileFingerprint(attributes.lastModifiedTime(), attributes.size()));
    } catch (IOException _) {
      return Optional.empty();
    }
  }
}
