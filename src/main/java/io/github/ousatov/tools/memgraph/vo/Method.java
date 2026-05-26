package io.github.ousatov.tools.memgraph.vo;

import io.github.ousatov.tools.memgraph.def.Const.Params;

/**
 * Method
 *
 * @author Oleksii Usatov
 * @since 01.05.2026
 */
@SuppressWarnings("java:S107")
public record Method(
    String ownerFqn,
    String signature,
    String name,
    String returnType,
    boolean isStatic,
    String visibility,
    int startLine,
    int endLine,
    boolean isSynthetic,
    String language,
    String kind) {

  public Method(
      String ownerFqn,
      String signature,
      String name,
      String returnType,
      boolean isStatic,
      String visibility,
      int startLine,
      int endLine,
      boolean isSynthetic) {
    this(
        ownerFqn,
        signature,
        name,
        returnType,
        isStatic,
        visibility,
        startLine,
        endLine,
        isSynthetic,
        "java",
        Params.METHOD);
  }
}
