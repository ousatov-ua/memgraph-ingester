package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.vo.analysis.module.AnnotationDecl;
import io.github.ousatov.tools.memgraph.vo.analysis.module.CallDecl;
import io.github.ousatov.tools.memgraph.vo.analysis.module.MemberDecl;
import io.github.ousatov.tools.memgraph.vo.analysis.module.RelationDecl;
import io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl;
import java.util.List;

/**
 * Common module-shaped analysis emitted by external language helpers.
 *
 * @author Oleksii Usatov
 */
public interface ModuleAnalysis {

  String moduleFqn();

  String moduleName();

  String packageName();

  String modulePath();

  int startLine();

  int endLine();

  List<TypeDecl> types();

  List<RelationDecl> relations();

  List<MemberDecl> members();

  List<AnnotationDecl> annotations();

  List<CallDecl> calls();
}
