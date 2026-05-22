package io.github.ousatov.tools.memgraph.exe;

import java.util.List;

/**
 * Neutral JavaScript/TypeScript structure emitted by the Node helper before graph writes.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings("java:S107")
public record JsAnalysis(
    String moduleFqn,
    String moduleName,
    String packageName,
    String modulePath,
    int startLine,
    int endLine,
    List<TypeDecl> types,
    List<MemberDecl> members,
    List<AnnotationDecl> annotations,
    List<CallDecl> calls) {

  public record TypeDecl(
      String kind,
      String fqn,
      String name,
      String framework,
      boolean hasConstructor,
      int startLine,
      int endLine) {}

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
