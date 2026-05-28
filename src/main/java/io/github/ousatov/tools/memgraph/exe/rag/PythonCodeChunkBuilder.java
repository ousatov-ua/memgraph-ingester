package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalysis;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.MemberChunk;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.TypeChunk;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds derived {@code :CodeChunk} rows from Python analysis.
 *
 * @author Oleksii Usatov
 */
public final class PythonCodeChunkBuilder extends CommonCodeChunkBuilder<PythonAnalysis> {

  private static final String LANGUAGE = SourceLanguage.PYTHON.graphName();

  public PythonCodeChunkBuilder() {
    super(PythonCodeChunkBuilder::analyze);
  }

  private static CodeChunkAnalysis analyze(PythonAnalysis analysis) {
    List<MemberChunk> members =
        new ArrayList<>(
            analysis.members().stream().map(PythonCodeChunkBuilder::memberChunk).toList());
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
        analysis.types().stream().map(PythonCodeChunkBuilder::typeChunk).toList(),
        List.copyOf(members));
  }

  private static TypeChunk typeChunk(PythonAnalysis.TypeDecl type) {
    return new TypeChunk(
        "Class",
        type.fqn(),
        type.fqn(),
        type.name(),
        type.kind(),
        type.startLine(),
        type.endLine());
  }

  private static MemberChunk memberChunk(PythonAnalysis.MemberDecl member) {
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
