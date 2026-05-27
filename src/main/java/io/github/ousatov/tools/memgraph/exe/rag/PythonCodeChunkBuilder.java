package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalysis;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.MemberChunk;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.TypeChunk;

/** Builds derived {@code :CodeChunk} rows from Python analysis. */
public final class PythonCodeChunkBuilder extends CommonCodeChunkBuilder<PythonAnalysis> {

  private static final String LANGUAGE = SourceLanguage.PYTHON.graphName();

  public PythonCodeChunkBuilder() {
    super(PythonCodeChunkBuilder::analyze);
  }

  private static CodeChunkAnalysis analyze(PythonAnalysis analysis) {
    return new CodeChunkAnalysis(
        LANGUAGE,
        analysis.moduleFqn(),
        analysis.moduleName(),
        analysis.startLine(),
        analysis.endLine(),
        analysis.types().stream().map(PythonCodeChunkBuilder::typeChunk).toList(),
        analysis.members().stream().map(PythonCodeChunkBuilder::memberChunk).toList());
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
