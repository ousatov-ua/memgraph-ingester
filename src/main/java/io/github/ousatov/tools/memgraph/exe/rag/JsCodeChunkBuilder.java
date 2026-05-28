package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.analyze.JsAnalysis;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.MemberChunk;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.TypeChunk;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds derived {@code :CodeChunk} rows from JavaScript/TypeScript analysis.
 *
 * @author Oleksii Usatov
 */
public final class JsCodeChunkBuilder extends CommonCodeChunkBuilder<JsAnalysis> {

  private static final String LANGUAGE = SourceLanguage.JAVASCRIPT.graphName();

  public JsCodeChunkBuilder() {
    super(JsCodeChunkBuilder::analyze);
  }

  private static CodeChunkAnalysis analyze(JsAnalysis analysis) {
    List<MemberChunk> members =
        new ArrayList<>(analysis.members().stream().map(JsCodeChunkBuilder::memberChunk).toList());
    analysis.types().stream()
        .filter(type -> Params.CLASS.equals(type.kind()) && !type.hasConstructor())
        .map(type -> MemberChunk.syntheticConstructor(type.fqn(), type.startLine(), type.endLine()))
        .forEach(members::add);
    return new CodeChunkAnalysis(
        LANGUAGE,
        analysis.moduleFqn(),
        analysis.moduleName(),
        analysis.startLine(),
        analysis.endLine(),
        analysis.types().stream().map(JsCodeChunkBuilder::typeChunk).toList(),
        List.copyOf(members));
  }

  private static TypeChunk typeChunk(JsAnalysis.TypeDecl type) {
    String label =
        Params.INTERFACE.equals(type.kind()) || "type".equals(type.kind()) ? "Interface" : "Class";
    return new TypeChunk(
        label, type.fqn(), type.fqn(), type.name(), type.kind(), type.startLine(), type.endLine());
  }

  private static MemberChunk memberChunk(JsAnalysis.MemberDecl member) {
    return new MemberChunk(
        member.ownerFqn(),
        member.memberType(),
        member.kind(),
        member.key(),
        member.name(),
        member.startLine(),
        member.endLine());
  }
}
