package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalysis;
import io.github.ousatov.tools.memgraph.vo.rag.MemberChunk;
import io.github.ousatov.tools.memgraph.vo.rag.TypeChunk;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds derived {@code :CodeChunk} rows from ctags fallback analysis.
 *
 * @author Oleksii Usatov
 */
public final class CtagsCodeChunkBuilder extends CommonCodeChunkBuilder<CtagsAnalysis> {

  public CtagsCodeChunkBuilder() {
    super(CtagsCodeChunkBuilder::analyze);
  }

  private static CodeChunkAnalysis analyze(CtagsAnalysis analysis) {
    String language = analysis.language().graphName();
    List<MemberChunk> members =
        new ArrayList<>(
            analysis.members().stream().map(CtagsCodeChunkBuilder::memberChunk).toList());
    analysis.types().stream()
        .filter(CtagsCodeChunkBuilder::hasSyntheticConstructor)
        .map(type -> MemberChunk.syntheticConstructor(type.fqn(), type.startLine(), type.endLine()))
        .forEach(members::add);
    return new CodeChunkAnalysis(
        language,
        analysis.moduleFqn(),
        analysis.moduleName(),
        analysis.startLine(),
        analysis.endLine(),
        analysis.types().stream().map(CtagsCodeChunkBuilder::typeChunk).toList(),
        List.copyOf(members));
  }

  private static TypeChunk typeChunk(
      io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl type) {
    return new TypeChunk(
        type.interfaceLike() ? Const.Labels.INTERFACE : Const.Labels.CLASS,
        type.fqn(),
        type.fqn(),
        type.name(),
        type.graphKind(),
        type.startLine(),
        type.endLine());
  }

  private static MemberChunk memberChunk(
      io.github.ousatov.tools.memgraph.vo.analysis.ctags.MemberDecl member) {
    return new MemberChunk(
        member.ownerFqn(),
        member.memberType(),
        member.graphKind(),
        member.fqnOrSignature(),
        member.name(),
        member.startLine(),
        member.endLine());
  }

  private static boolean hasSyntheticConstructor(
      io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl type) {
    return Params.CLASS.equals(type.graphKind()) && Params.CLASS.equals(nodeKind(type));
  }

  private static String nodeKind(io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl type) {
    String rawKind = type.rawKind();
    return rawKind == null || rawKind.isBlank() ? type.graphKind() : rawKind;
  }
}
