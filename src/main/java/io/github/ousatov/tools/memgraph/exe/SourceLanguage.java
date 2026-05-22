package io.github.ousatov.tools.memgraph.exe;

/** Source language selected for one ingestion run. */
public enum SourceLanguage {
  JAVA("java"),
  JAVASCRIPT("javascript");

  private final String graphName;

  SourceLanguage(String graphName) {
    this.graphName = graphName;
  }

  public String graphName() {
    return graphName;
  }

  public static SourceLanguage parse(String value) {
    if (value == null || value.isBlank()) {
      return JAVA;
    }
    return switch (value.trim().toLowerCase()) {
      case "java" -> JAVA;
      case "js", "javascript", "ts", "typescript" -> JAVASCRIPT;
      default -> throw new IllegalArgumentException("Unsupported language: " + value);
    };
  }
}
