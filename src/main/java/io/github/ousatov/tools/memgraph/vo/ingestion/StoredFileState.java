package io.github.ousatov.tools.memgraph.vo.ingestion;

import java.util.Map;
import java.util.Set;

/**
 * Previously stored graph state for source files.
 *
 * @author Oleksii Usatov
 */
public record StoredFileState(
    Map<String, Long> lastModifiedByPath,
    Set<String> pathsMissingCodeChunks,
    boolean reliableExistingPaths) {

  /** Returns an empty reliable state. */
  public static StoredFileState empty() {
    return new StoredFileState(Map.of(), Set.of(), true);
  }

  /** Returns an empty state whose existing paths could not be read reliably. */
  public static StoredFileState unreliable() {
    return new StoredFileState(Map.of(), Set.of(), false);
  }
}
