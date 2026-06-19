package io.github.ousatov.tools.memgraph.vo;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;

/**
 * Method
 *
 * @author Oleksii Usatov
 * @since 01.05.2026
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
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
    String kind,
    String ownerKind) {

  public Method(
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
        language,
        kind,
        Const.Symbols.EMPTY);
  }

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
        Const.SystemParams.JAVA,
        Params.METHOD,
        Const.Symbols.EMPTY);
  }
}
