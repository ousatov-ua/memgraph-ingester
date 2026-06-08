package io.github.ousatov.tools.memgraph.vo.rag;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;

/**
 * Member source range used to build code RAG chunks.
 *
 * @author Oleksii Usatov
 */
public record MemberChunk(
    String ownerFqn,
    String memberType,
    String kind,
    String key,
    String name,
    int startLine,
    int endLine,
    boolean synthetic) {

  public MemberChunk(
      String ownerFqn,
      String memberType,
      String kind,
      String key,
      String name,
      int startLine,
      int endLine) {
    this(ownerFqn, memberType, kind, key, name, startLine, endLine, startLine <= 0 || endLine <= 0);
  }

  /** Builds the synthetic constructor chunk used by graph writers for implicit constructors. */
  public static MemberChunk syntheticConstructor(String ownerFqn, int startLine, int endLine) {
    return new MemberChunk(
        ownerFqn,
        Params.CONSTRUCTOR,
        Params.CONSTRUCTOR,
        ownerFqn + Const.Symbols.DOT + Labels.INIT + Const.Symbols.PARENS,
        Labels.INIT,
        startLine,
        endLine,
        true);
  }
}
