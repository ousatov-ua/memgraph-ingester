package io.github.ousatov.tools.memgraph.vo.analysis;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.analyze.ModuleAnalysis;
import java.util.List;

/**
 * Neutral JavaScript/TypeScript structure emitted by the Node helper before graph writes.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
public record JsAnalysis(
    String moduleFqn,
    String moduleName,
    String packageName,
    String modulePath,
    int startLine,
    int endLine,
    List<io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl> types,
    List<io.github.ousatov.tools.memgraph.vo.analysis.module.RelationDecl> relations,
    List<io.github.ousatov.tools.memgraph.vo.analysis.module.MemberDecl> members,
    List<io.github.ousatov.tools.memgraph.vo.analysis.module.AnnotationDecl> annotations,
    List<io.github.ousatov.tools.memgraph.vo.analysis.module.CallDecl> calls)
    implements ModuleAnalysis {}
