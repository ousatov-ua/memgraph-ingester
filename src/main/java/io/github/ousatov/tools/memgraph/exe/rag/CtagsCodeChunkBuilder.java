package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalysis;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.MemberChunk;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.TypeChunk;

/** Builds derived {@code :CodeChunk} rows from ctags fallback analysis. */
public final class CtagsCodeChunkBuilder extends CommonCodeChunkBuilder<CtagsAnalysis> {

  public CtagsCodeChunkBuilder() {
    super(CtagsCodeChunkBuilder::analyze);
  }

  private static CodeChunkAnalysis analyze(CtagsAnalysis analysis) {
    String language = analysis.language().graphName();
    return new CodeChunkAnalysis(
        language,
        analysis.moduleFqn(),
        analysis.moduleName(),
        analysis.startLine(),
        analysis.endLine(),
        analysis.types().stream().map(CtagsCodeChunkBuilder::typeChunk).toList(),
        analysis.members().stream().map(CtagsCodeChunkBuilder::memberChunk).toList());
  }

  private static TypeChunk typeChunk(CtagsAnalysis.TypeDecl type) {
    return new TypeChunk(
        type.interfaceLike() ? "Interface" : "Class",
        type.fqn(),
        type.fqn(),
        type.name(),
        type.graphKind(),
        type.startLine(),
        type.endLine());
  }

  private static MemberChunk memberChunk(CtagsAnalysis.MemberDecl member) {
    return new MemberChunk(
        member.ownerFqn(),
        member.memberType(),
        member.graphKind(),
        member.fqnOrSignature(),
        member.name(),
        member.startLine(),
        member.endLine());
  }
}
