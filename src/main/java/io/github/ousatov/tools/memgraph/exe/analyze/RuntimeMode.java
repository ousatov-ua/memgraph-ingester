package io.github.ousatov.tools.memgraph.exe.analyze;

/**
 * How JavaScript parsing obtains a Node.js executable.
 *
 * @author Oleksii Usatov
 */
public enum RuntimeMode {
  MANAGED,
  SYSTEM,
  OFFLINE;

  /** Parses a CLI/runtime-mode value, defaulting blank input to managed runtime mode. */
  public static RuntimeMode parse(String value) {
    if (value == null || value.isBlank()) {
      return MANAGED;
    }
    return switch (value.trim().toLowerCase()) {
      case "managed" -> MANAGED;
      case "system" -> SYSTEM;
      case "offline" -> OFFLINE;
      default -> throw new IllegalArgumentException("Unsupported JS runtime mode: " + value);
    };
  }
}
