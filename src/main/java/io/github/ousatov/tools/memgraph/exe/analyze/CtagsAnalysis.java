package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import java.util.List;

/**
 * Source structure emitted by Universal Ctags before graph writes.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings("java:S107")
public record CtagsAnalysis(
    SourceLanguage language,
    String moduleFqn,
    String moduleName,
    String packageName,
    String modulePath,
    int startLine,
    int endLine,
    List<TypeDecl> types,
    List<MemberDecl> members) {

  public record TypeDecl(
      String graphKind,
      String rawKind,
      String fqn,
      String name,
      boolean interfaceLike,
      int startLine,
      int endLine) {}

  public record MemberDecl(
      String ownerFqn,
      String memberType,
      String graphKind,
      String fqnOrSignature,
      String name,
      String dataType,
      boolean isStatic,
      String visibility,
      int startLine,
      int endLine) {}
}
