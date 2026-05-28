package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.ModuleAnalysis;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.MemberChunk;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.TypeChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Builds code chunks from common module-shaped analysis records.
 *
 * @author Oleksii Usatov
 */
public abstract class ModuleCodeChunkBuilder<T extends ModuleAnalysis>
    extends CommonCodeChunkBuilder<T> {

  protected ModuleCodeChunkBuilder(
      String language, Function<ModuleAnalysis.TypeDecl, String> typeLabel) {
    super(analysis -> analyze(language, analysis, typeLabel));
  }

  private static CodeChunkAnalysis analyze(
      String language,
      ModuleAnalysis analysis,
      Function<ModuleAnalysis.TypeDecl, String> typeLabel) {
    List<MemberChunk> members =
        new ArrayList<>(
            analysis.members().stream().map(ModuleCodeChunkBuilder::memberChunk).toList());
    analysis.types().stream()
        .filter(type -> Params.CLASS.equals(type.kind()) && !type.hasConstructor())
        .map(type -> MemberChunk.syntheticConstructor(type.fqn(), type.startLine(), type.endLine()))
        .forEach(members::add);
    return new CodeChunkAnalysis(
        language,
        analysis.moduleFqn(),
        analysis.moduleName(),
        analysis.startLine(),
        analysis.endLine(),
        analysis.types().stream().map(type -> typeChunk(type, typeLabel.apply(type))).toList(),
        List.copyOf(members));
  }

  private static TypeChunk typeChunk(ModuleAnalysis.TypeDecl type, String label) {
    return new TypeChunk(
        label, type.fqn(), type.fqn(), type.name(), type.kind(), type.startLine(), type.endLine());
  }

  private static MemberChunk memberChunk(ModuleAnalysis.MemberDecl member) {
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
