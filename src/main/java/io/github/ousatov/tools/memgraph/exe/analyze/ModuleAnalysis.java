package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const;
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

  /** Common type declaration record. */
  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
  record TypeDecl(
      String kind,
      String fqn,
      String name,
      String framework,
      boolean hasConstructor,
      boolean isAbstract,
      int startLine,
      int endLine) {}

  /** Common type relation record. */
  record RelationDecl(String kind, String childFqn, String targetFqn) {}

  /** Common member declaration record. */
  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
  record MemberDecl(
      String ownerFqn,
      String memberType,
      String kind,
      String key,
      String name,
      String dataType,
      boolean isStatic,
      int startLine,
      int endLine) {}

  /** Common annotation or decorator record. */
  record AnnotationDecl(String ownerKind, String ownerKey, String fqn, String name) {}

  /** Common call edge record. */
  record CallDecl(
      String callerSignature, String calleeSignature, String calleeOwnerFqn, String calleeName) {}
}
