package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.util.List;

/**
 * Normalized source structure used by common CodeChunk generation.
 *
 * @author Oleksii Usatov
 */
public record CodeChunkAnalysis(
    String language,
    String moduleFqn,
    String moduleName,
    int startLine,
    int endLine,
    List<TypeChunk> types,
    List<MemberChunk> members) {

  public record TypeChunk(
      String sourceLabel,
      String sourceId,
      String ownerFqn,
      String name,
      String kind,
      int startLine,
      int endLine) {}

  public record MemberChunk(
      String ownerFqn,
      String memberType,
      String kind,
      String key,
      String name,
      int startLine,
      int endLine) {

    /** Builds the synthetic constructor chunk used by graph writers for implicit constructors. */
    public static MemberChunk syntheticConstructor(String ownerFqn, int startLine, int endLine) {
      return new MemberChunk(
          ownerFqn,
          Params.CONSTRUCTOR,
          Params.CONSTRUCTOR,
          ownerFqn + "." + Labels.INIT + "()",
          Labels.INIT,
          startLine,
          endLine);
    }
  }
}
