package io.github.ousatov.tools.memgraph.vo.adapter;

import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;

/**
 * Cached detected source language with the file fingerprint it was derived from.
 *
 * @author Oleksii Usatov
 */
public record DetectedLanguage(SourceLanguage language, FileFingerprint fingerprint) {

  /** Returns true when the cached detection still matches the current fingerprint. */
  public boolean matches(FileFingerprint current) {
    return fingerprint.equals(current);
  }
}
