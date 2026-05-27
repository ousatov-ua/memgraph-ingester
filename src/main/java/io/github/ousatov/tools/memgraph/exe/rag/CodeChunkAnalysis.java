package io.github.ousatov.tools.memgraph.exe.rag;

import java.util.List;

/** Normalized source structure used by common CodeChunk generation. */
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
      int endLine) {}
}
