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
    List<ModuleAnalysis.TypeDecl> types,
    List<ModuleAnalysis.RelationDecl> relations,
    List<ModuleAnalysis.MemberDecl> members,
    List<ModuleAnalysis.AnnotationDecl> annotations,
    List<ModuleAnalysis.CallDecl> calls)
    implements ModuleAnalysis {}
