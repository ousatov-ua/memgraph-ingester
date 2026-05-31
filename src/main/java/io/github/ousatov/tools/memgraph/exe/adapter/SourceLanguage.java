package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Source language identity used to scope code nodes in the graph.
 *
 * @author Oleksii Usatov
 */
public final class SourceLanguage {

  public static final SourceLanguage JAVA = new SourceLanguage(Const.SystemParams.JAVA, "Java");
  public static final SourceLanguage JAVASCRIPT = new SourceLanguage("js", "Js");
  public static final SourceLanguage PYTHON =
      new SourceLanguage(Const.SystemParams.PYTHON, Const.SystemParams.PYTHON_DISPLAY);

  private final String graphName;
  private final String nodeName;

  private SourceLanguage(String graphName, String nodeName) {
    this.graphName = Objects.requireNonNull(graphName, "graphName");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
  }

  /** Returns a source language for a stable graph name and display node name. */
  public static SourceLanguage of(String graphName, String nodeName) {
    String normalizedGraphName = normalizeGraphName(graphName);
    String normalizedNodeName =
        nodeName == null || nodeName.isBlank() ? normalizedGraphName : nodeName.trim();
    if (JAVA.graphName.equals(normalizedGraphName)) {
      return JAVA;
    }
    if (JAVASCRIPT.graphName.equals(normalizedGraphName)) {
      return JAVASCRIPT;
    }
    if (PYTHON.graphName.equals(normalizedGraphName)) {
      return PYTHON;
    }
    return new SourceLanguage(normalizedGraphName, normalizedNodeName);
  }

  /** Normalizes a Universal Ctags language name into a graph language identity. */
  public static SourceLanguage fromCtagsName(String ctagsLanguageName) {
    String displayName = ctagsLanguageName == null ? Const.Symbols.EMPTY : ctagsLanguageName.trim();
    String graphName =
        switch (displayName.toLowerCase(Locale.ROOT)) {
          case "c++" -> Const.SystemParams.CPP;
          case "c#" -> Const.SystemParams.CSHARP;
          case "f#" -> Const.SystemParams.FSHARP;
          case "javascript" -> JAVASCRIPT.graphName;
          case Const.SystemParams.TYPESCRIPT -> JAVASCRIPT.graphName;
          default -> normalizeGraphName(displayName);
        };
    String nodeName =
        switch (graphName) {
          case Const.SystemParams.CPP -> "C++";
          case Const.SystemParams.CSHARP -> "C#";
          case Const.SystemParams.FSHARP -> "F#";
          default -> displayName.isBlank() ? graphName : displayName;
        };
    return of(graphName, nodeName);
  }

  /** Returns first-class languages supported by dedicated parsers. */
  public static List<SourceLanguage> supported() {
    return List.of(JAVA, JAVASCRIPT, PYTHON);
  }

  public String graphName() {
    return graphName;
  }

  public String nodeName() {
    return nodeName;
  }

  /** Returns true for one of the built-in first-class parser languages. */
  public boolean isFirstClass() {
    return supported().contains(this);
  }

  /** Returns the Cypher query that batch-fetches stored {@code lastModified} values per file. */
  public String filesLastModifiedCypher() {
    if (JAVA.equals(this)) {
      return Cypher.CYPHER_GET_JAVA_FILES_LAST_MODIFIED;
    }
    if (JAVASCRIPT.equals(this)) {
      return Cypher.CYPHER_GET_JAVASCRIPT_FILES_LAST_MODIFIED;
    }
    if (PYTHON.equals(this)) {
      return Cypher.CYPHER_GET_PYTHON_FILES_LAST_MODIFIED;
    }
    return Cypher.CYPHER_GET_CTAGS_FILES_LAST_MODIFIED;
  }

  /** Returns the Cypher query that lists project file paths under the active source root. */
  public String filesInSourceRootCypher() {
    if (JAVA.equals(this)) {
      return Cypher.CYPHER_GET_JAVA_FILES_IN_SOURCE_ROOT;
    }
    if (JAVASCRIPT.equals(this)) {
      return Cypher.CYPHER_GET_JAVASCRIPT_FILES_IN_SOURCE_ROOT;
    }
    if (PYTHON.equals(this)) {
      return Cypher.CYPHER_GET_PYTHON_FILES_IN_SOURCE_ROOT;
    }
    return Cypher.CYPHER_GET_CTAGS_FILES_IN_SOURCE_ROOT;
  }

  /** Returns the Cypher query that retrieves the stored source-root hint for one file. */
  public String sourceRootHintCypher() {
    if (JAVA.equals(this)) {
      return Cypher.CYPHER_GET_JAVA_SOURCE_ROOT_HINT_FOR_FILE;
    }
    if (JAVASCRIPT.equals(this)) {
      return Cypher.CYPHER_GET_JAVASCRIPT_SOURCE_ROOT_HINT_FOR_FILE;
    }
    if (PYTHON.equals(this)) {
      return Cypher.CYPHER_GET_PYTHON_SOURCE_ROOT_HINT_FOR_FILE;
    }
    return Cypher.CYPHER_GET_CTAGS_SOURCE_ROOT_HINT_FOR_FILE;
  }

  /** Returns Cypher queries that drop legacy module-FQN-keyed declarations for one file. */
  public Optional<StaleModuleDefinitionCypher> staleModuleDefinitionsCypher() {
    if (JAVASCRIPT.equals(this)) {
      return Optional.of(
          new StaleModuleDefinitionCypher(
              Cypher.CYPHER_DELETE_STALE_JAVASCRIPT_MEMBERS_FOR_FILE,
              Cypher.CYPHER_DELETE_STALE_JAVASCRIPT_OWNERS_FOR_FILE,
              Cypher.CYPHER_DELETE_EMPTY_JAVASCRIPT_PACKAGES));
    }
    if (PYTHON.equals(this)) {
      return Optional.of(
          new StaleModuleDefinitionCypher(
              Cypher.CYPHER_DELETE_STALE_PYTHON_MEMBERS_FOR_FILE,
              Cypher.CYPHER_DELETE_STALE_PYTHON_OWNERS_FOR_FILE,
              Cypher.CYPHER_DELETE_EMPTY_PYTHON_PACKAGES));
    }
    return Optional.empty();
  }

  /** Triplet of Cypher queries that purge legacy module-FQN-keyed declarations for one file. */
  public record StaleModuleDefinitionCypher(String members, String owners, String emptyPackages) {}

  @Override
  public boolean equals(Object other) {
    return other instanceof SourceLanguage language && graphName.equals(language.graphName);
  }

  @Override
  public int hashCode() {
    return graphName.hashCode();
  }

  @Override
  public String toString() {
    return graphName;
  }

  private static String normalizeGraphName(String rawName) {
    String lower = rawName == null ? Const.Symbols.EMPTY : rawName.trim().toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder();
    boolean previousSeparator = false;
    for (int index = 0; index < lower.length(); index++) {
      char current = lower.charAt(index);
      if (Character.isLetterOrDigit(current)) {
        out.append(current);
        previousSeparator = false;
      } else if (!previousSeparator && !out.isEmpty()) {
        out.append('_');
        previousSeparator = true;
      }
    }
    while (!out.isEmpty() && out.charAt(out.length() - 1) == '_') {
      out.deleteCharAt(out.length() - 1);
    }
    if (out.isEmpty()) {
      throw new IllegalArgumentException("Source language graph name must not be blank");
    }
    return out.toString();
  }
}
