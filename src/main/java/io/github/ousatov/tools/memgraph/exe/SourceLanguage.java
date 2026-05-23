package io.github.ousatov.tools.memgraph.exe;

import java.util.List;

/**
 * Supported source language group in the code graph.
 *
 * @author Oleksii Usatov
 */
public enum SourceLanguage {
  JAVA("java", "Java"),
  JAVASCRIPT("js", "Js");

  private final String graphName;
  private final String nodeName;

  SourceLanguage(String graphName, String nodeName) {
    this.graphName = graphName;
    this.nodeName = nodeName;
  }

  public String graphName() {
    return graphName;
  }

  public String nodeName() {
    return nodeName;
  }

  public static List<SourceLanguage> supported() {
    return List.of(values());
  }
}
