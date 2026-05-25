package io.github.ousatov.tools.memgraph.exe.analyze;

import java.util.List;

/**
 * Neutral Python source structure emitted by the Python helper before graph writes.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings({"java:S107", "java:S1186"})
public record PythonAnalysis(
    String moduleFqn,
    String moduleName,
    String packageName,
    String modulePath,
    int startLine,
    int endLine,
    List<TypeDecl> types,
    List<RelationDecl> relations,
    List<MemberDecl> members,
    List<AnnotationDecl> annotations,
    List<CallDecl> calls) {

  public record TypeDecl(
      String kind,
      String fqn,
      String name,
      String framework,
      boolean hasConstructor,
      boolean isAbstract,
      int startLine,
      int endLine) {}

  public record RelationDecl(String kind, String childFqn, String targetFqn) {}

  @SuppressWarnings("java:S107")
  public record MemberDecl(
      String ownerFqn,
      String memberType,
      String kind,
      String key,
      String name,
      String dataType,
      boolean isStatic,
      int startLine,
      int endLine) {}

  public record AnnotationDecl(String ownerKind, String ownerKey, String fqn, String name) {}

  public record CallDecl(
      String callerSignature, String calleeSignature, String calleeOwnerFqn, String calleeName) {}
}
