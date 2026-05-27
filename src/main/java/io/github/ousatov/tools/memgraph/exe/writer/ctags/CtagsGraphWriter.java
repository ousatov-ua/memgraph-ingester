package io.github.ousatov.tools.memgraph.exe.writer.ctags;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.writer.CommonGraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.FieldWrite;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Writes Universal Ctags fallback graph structures for a detected source language.
 *
 * @author Oleksii Usatov
 */
public final class CtagsGraphWriter extends CommonGraphWriter {

  public CtagsGraphWriter(Dependencies dependencies) {
    super(dependencies);
  }

  /** Upserts the synthetic module owner used for file-level ctags declarations. */
  public void upsertModule(
      Path file,
      SourceLanguage language,
      String pkg,
      String fqn,
      String name,
      String modulePath,
      int startLine,
      int endLine) {
    upsertClassNode(
        file,
        pkg,
        fqn,
        name,
        false,
        "",
        false,
        false,
        false,
        language.graphName(),
        Params.MODULE,
        modulePath,
        "");
    upsertMethodNode(
        file,
        new Method(
            fqn,
            fqn + "." + Labels.INIT + "()",
            Labels.INIT,
            Labels.VOID,
            true,
            "",
            startLine,
            endLine,
            true,
            language.graphName(),
            Params.MODULE));
  }

  /** Upserts a type as either a {@code :Class} or {@code :Interface} graph node. */
  @SuppressWarnings("java:S107")
  public void upsertType(
      Path file,
      SourceLanguage language,
      String pkg,
      String fqn,
      String name,
      String graphKind,
      String rawKind,
      boolean interfaceLike,
      int startLine,
      int endLine) {
    String nodeKind = nodeKind(graphKind, rawKind);
    if (interfaceLike) {
      upsertInterfaceNode(file, pkg, fqn, name, true, "", language.graphName(), nodeKind, "", "");
      return;
    }
    upsertClassNode(
        file,
        pkg,
        fqn,
        name,
        false,
        "",
        Params.ENUM.equals(graphKind),
        false,
        false,
        language.graphName(),
        nodeKind,
        "",
        "");
    if (Params.CLASS.equals(graphKind) && Params.CLASS.equals(nodeKind)) {
      upsertMethodNode(
          file,
          new Method(
              fqn,
              fqn + "." + Labels.INIT + "()",
              Labels.INIT,
              Labels.VOID,
              false,
              "",
              startLine,
              endLine,
              true,
              language.graphName(),
              Params.CONSTRUCTOR));
    }
  }

  private static String nodeKind(String graphKind, String rawKind) {
    if (rawKind == null || rawKind.isBlank()) {
      return graphKind;
    }
    return rawKind.trim().toLowerCase(Locale.ROOT);
  }

  /** Upserts field and method declarations emitted by ctags. */
  public void upsertMembers(Path file, Collection<FieldWrite> fields, Collection<Method> methods) {
    upsertFieldNodes(file, fields);
    upsertMethodNodes(file, methods);
  }

  /** Creates a field payload for the dynamic language. */
  public static FieldWrite field(
      SourceLanguage language,
      String ownerFqn,
      String fqn,
      String name,
      String type,
      boolean isStatic,
      String visibility,
      String kind) {
    return new FieldWrite(
        ownerFqn, fqn, name, type, isStatic, visibility, language.graphName(), kind);
  }

  /** Creates a method payload for the dynamic language. */
  @SuppressWarnings("java:S107")
  public static Method method(
      SourceLanguage language,
      String ownerFqn,
      String signature,
      String name,
      String returnType,
      boolean isStatic,
      String visibility,
      int startLine,
      int endLine,
      String kind) {
    return new Method(
        ownerFqn,
        signature,
        name,
        returnType,
        isStatic,
        visibility,
        startLine,
        endLine,
        false,
        language.graphName(),
        kind);
  }

  /** Returns an empty immutable field collection with a precise generic type. */
  public static Collection<FieldWrite> noFields() {
    return List.of();
  }

  /** Returns an empty immutable method collection with a precise generic type. */
  public static Collection<Method> noMethods() {
    return List.of();
  }
}
