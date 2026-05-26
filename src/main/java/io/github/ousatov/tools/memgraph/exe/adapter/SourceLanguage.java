package io.github.ousatov.tools.memgraph.exe.adapter;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Source language identity used to scope code nodes in the graph.
 *
 * @author Oleksii Usatov
 */
public final class SourceLanguage {

  public static final SourceLanguage JAVA = new SourceLanguage("java", "Java");
  public static final SourceLanguage JAVASCRIPT = new SourceLanguage("js", "Js");
  public static final SourceLanguage PYTHON = new SourceLanguage("python", "Python");

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
    String displayName = ctagsLanguageName == null ? "" : ctagsLanguageName.trim();
    String graphName =
        switch (displayName.toLowerCase(Locale.ROOT)) {
          case "c++" -> "cpp";
          case "c#" -> "csharp";
          case "f#" -> "fsharp";
          case "javascript" -> JAVASCRIPT.graphName;
          case "typescript" -> JAVASCRIPT.graphName;
          default -> normalizeGraphName(displayName);
        };
    String nodeName =
        switch (graphName) {
          case "cpp" -> "C++";
          case "csharp" -> "C#";
          case "fsharp" -> "F#";
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
    String lower = rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
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
