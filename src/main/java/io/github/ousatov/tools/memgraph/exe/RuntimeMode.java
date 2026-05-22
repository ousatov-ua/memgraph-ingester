package io.github.ousatov.tools.memgraph.exe;

/** How JavaScript parsing obtains a Node.js executable. */
public enum RuntimeMode {
  MANAGED,
  SYSTEM,
  OFFLINE;

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
