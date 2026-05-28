package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stable graph identifiers for Universal Ctags fallback declarations.
 *
 * @author Oleksii Usatov
 */
public final class CtagsNames {

  private CtagsNames() {
    // Utility class.
  }

  /** Returns a package name matching the existing language-prefixed synthetic style. */
  public static String packageName(SourceLanguage language, Path sourceRoot, Path file) {
    Path parent = relativePath(sourceRoot, file).getParent();
    String parentPackage = parent == null ? Const.Symbols.EMPTY : encodePath(parent);
    return parentPackage.isBlank()
        ? language.graphName()
        : language.graphName() + Const.Symbols.DOT + parentPackage;
  }

  /** Returns a normalized source-root-relative module path. */
  public static String modulePath(Path sourceRoot, Path file) {
    return relativePath(sourceRoot, file).toString().replace('\\', '/');
  }

  /** Returns the synthetic module FQN for a file in a detected language. */
  public static String moduleFqn(SourceLanguage language, Path sourceRoot, Path file) {
    return language.graphName() + Const.Symbols.DOT + encodePath(relativePath(sourceRoot, file));
  }

  /** Returns a type or member FQN beneath an owner FQN. */
  public static String childFqn(String ownerFqn, String rawName) {
    return ownerFqn + Const.Symbols.DOT + encodeQualifiedName(rawName);
  }

  /** Returns a method signature beneath an owner FQN. */
  public static String methodSignature(String ownerFqn, String rawName, String ctagsSignature) {
    return ownerFqn
        + Const.Symbols.DOT
        + encodeName(rawName)
        + Const.Symbols.LEFT_PAREN
        + parameters(ctagsSignature)
        + Const.Symbols.RIGHT_PAREN;
  }

  /** Encodes a dotted or scope-qualified ctags name. */
  public static String encodeQualifiedName(String rawName) {
    if (rawName == null || rawName.isBlank()) {
      return Params.DEFAULT_NAME;
    }
    String normalized =
        rawName
            .trim()
            .replace(Const.Symbols.DOUBLE_COLON, Const.Symbols.DOT)
            .replace('/', '.')
            .replace('\\', '.');
    List<String> parts = new ArrayList<>();
    for (String part : normalized.split(Const.Symbols.DOT_REGEX)) {
      if (!part.isBlank()) {
        parts.add(encodeName(part));
      }
    }
    return parts.isEmpty() ? Params.DEFAULT_NAME : String.join(Const.Symbols.DOT, parts);
  }

  static Path relativePath(Path sourceRoot, Path file) {
    return LanguageAdapter.localPath(sourceRoot, file).normalize();
  }

  private static String encodePath(Path path) {
    List<String> segments = new ArrayList<>();
    for (Path segment : path) {
      segments.add(encodeName(segment.toString()));
    }
    return String.join(Const.Symbols.DOT, segments);
  }

  private static String encodeName(String rawName) {
    String value = rawName == null ? Const.Symbols.EMPTY : rawName.trim();
    if (value.isBlank()) {
      return Params.DEFAULT_NAME;
    }
    StringBuilder out = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isLetterOrDigit(current) || current == '_') {
        out.append(current);
      } else {
        out.append('$').append(Integer.toHexString(current));
      }
    }
    return out.toString();
  }

  private static String parameters(String ctagsSignature) {
    if (ctagsSignature == null || ctagsSignature.isBlank()) {
      return Const.Symbols.EMPTY;
    }
    String trimmed = ctagsSignature.trim();
    if (trimmed.startsWith(Const.Symbols.LEFT_PAREN)
        && trimmed.endsWith(Const.Symbols.RIGHT_PAREN)
        && trimmed.length() >= 2) {
      trimmed = trimmed.substring(1, trimmed.length() - 1);
    }
    if (trimmed.isBlank()) {
      return Const.Symbols.EMPTY;
    }
    List<String> params = new ArrayList<>();
    for (String part : trimmed.split(",")) {
      params.add(encodeQualifiedName(part.trim().replaceAll("\\s+", Const.Symbols.SPACE)));
    }
    return String.join(Const.Symbols.COMMA_SPACE, params);
  }

  private static final class Params {

    private static final String DEFAULT_NAME = "$default";

    private Params() {
      // Utility class.
    }
  }
}
