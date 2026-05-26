package io.github.ousatov.tools.memgraph.exe.analyze;

/**
 * How managed parser runtimes obtain executable dependencies.
 *
 * @author Oleksii Usatov
 */
public enum RuntimeMode {
  MANAGED,
  SYSTEM,
  OFFLINE;

  /** Parses a CLI/runtime-mode value, defaulting blank input to managed runtime mode. */
  public static RuntimeMode parse(String value) {
    return parse(value, "parser");
  }

  /** Parses a CLI/runtime-mode value for a named runtime family. */
  public static RuntimeMode parse(String value, String runtimeName) {
    if (value == null || value.isBlank()) {
      return MANAGED;
    }
    return switch (value.trim().toLowerCase()) {
      case "managed" -> MANAGED;
      case "system" -> SYSTEM;
      case "offline" -> OFFLINE;
      default ->
          throw new IllegalArgumentException(
              "Unsupported " + runtimeName + " runtime mode: " + value);
    };
  }
}
