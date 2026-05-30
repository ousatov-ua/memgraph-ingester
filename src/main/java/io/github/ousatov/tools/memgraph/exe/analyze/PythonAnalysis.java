package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const;
import java.util.List;

/**
 * Neutral Python source structure emitted by the Python helper before graph writes.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
public record PythonAnalysis(
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
