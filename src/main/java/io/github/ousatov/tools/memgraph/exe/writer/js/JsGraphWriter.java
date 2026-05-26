package io.github.ousatov.tools.memgraph.exe.writer.js;

import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.writer.CommonGraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.FieldWrite;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Writes JavaScript/TypeScript-specific graph structures.
 *
 * @author Oleksii Usatov
 */
public final class JsGraphWriter extends CommonGraphWriter {

  public JsGraphWriter(Dependencies dependencies) {
    super(dependencies);
  }

  /** Upserts the synthetic module owner used for top-level JavaScript declarations. */
  public void upsertModule(
      Path file,
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
        JAVASCRIPT_LANGUAGE,
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
            JAVASCRIPT_LANGUAGE,
            Params.MODULE));
  }

  /** Upserts a JavaScript/TypeScript class declaration using the existing {@code :Class} label. */
  @SuppressWarnings("java:S107")
  public void upsertClass(
      Path file,
      String pkg,
      String fqn,
      String name,
      String modulePath,
      String framework,
      boolean isAbstract,
      boolean hasDeclaredConstructor,
      int startLine,
      int endLine) {
    upsertTypeClass(
        file,
        pkg,
        fqn,
        name,
        modulePath,
        framework,
        false,
        isAbstract,
        Params.CLASS,
        startLine,
        endLine);
    if (!hasDeclaredConstructor) {
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
              JAVASCRIPT_LANGUAGE,
              Params.CONSTRUCTOR));
    }
  }

  /** Upserts a TypeScript enum using the existing {@code :Class} label and enum metadata. */
  public void upsertEnum(
      Path file,
      String pkg,
      String fqn,
      String name,
      String modulePath,
      int startLine,
      int endLine) {
    upsertTypeClass(
        file, pkg, fqn, name, modulePath, "", true, false, Params.ENUM, startLine, endLine);
  }

  /** Writes a JavaScript/TypeScript class {@code EXTENDS} relation. */
  public void upsertExtendsClass(String childFqn, String parentFqn) {
    upsertTypeRelation(
        Cypher.CYPHER_UPSERT_EXTENDS_CLASS,
        childFqn,
        parentFqn,
        Params.PARENT,
        Params.PARENT_NAME,
        Params.PARENT_PKG,
        JAVASCRIPT_LANGUAGE);
  }

  /** Writes a JavaScript/TypeScript interface {@code EXTENDS} relation. */
  public void upsertInterfaceExtends(String childFqn, String parentFqn) {
    upsertTypeRelation(
        Cypher.CYPHER_UPSERT_INTERFACE_EXTENDS,
        childFqn,
        parentFqn,
        Params.PARENT,
        Params.PARENT_NAME,
        Params.PARENT_PKG,
        JAVASCRIPT_LANGUAGE);
  }

  /** Writes a JavaScript/TypeScript class {@code IMPLEMENTS} relation. */
  public void upsertImplements(String childFqn, String interfaceFqn) {
    upsertTypeRelation(
        Cypher.CYPHER_UPSERT_IMPLEMENTS,
        childFqn,
        interfaceFqn,
        Params.IFACE,
        Params.IFACE_NAME,
        Params.IFACE_PKG,
        JAVASCRIPT_LANGUAGE);
  }

  /** Upserts a TypeScript interface or type alias using the compatible {@code :Interface} label. */
  public void upsertInterface(
      Path file,
      String pkg,
      String fqn,
      String name,
      String kind,
      String modulePath,
      String framework) {
    upsertInterfaceNode(
        file, pkg, fqn, name, true, "", JAVASCRIPT_LANGUAGE, kind, modulePath, framework);
  }

  /** Upserts a JavaScript/TypeScript property or top-level variable as a {@code :Field}. */
  public void upsertField(
      Path file,
      String ownerFqn,
      String fqn,
      String name,
      String type,
      boolean isStatic,
      String kind) {
    upsertFieldNodes(
        file,
        List.of(
            new FieldWrite(ownerFqn, fqn, name, type, isStatic, "", JAVASCRIPT_LANGUAGE, kind)));
  }

  /** Upserts a JavaScript/TypeScript function or method as a {@code :Method}. */
  @SuppressWarnings("java:S107")
  public void upsertMethod(
      Path file,
      String ownerFqn,
      String signature,
      String name,
      String returnType,
      boolean isStatic,
      int startLine,
      int endLine,
      String kind) {
    upsertMethodNode(
        file,
        new Method(
            ownerFqn,
            signature,
            name,
            returnType,
            isStatic,
            "",
            startLine,
            endLine,
            false,
            JAVASCRIPT_LANGUAGE,
            kind));
  }

  /** Upserts prebuilt JavaScript/TypeScript members in batches. */
  public void upsertMembers(Path file, Collection<FieldWrite> fields, Collection<Method> methods) {
    upsertFieldNodes(file, fields);
    upsertMethodNodes(file, methods);
  }

  @SuppressWarnings("java:S107")
  private void upsertTypeClass(
      Path file,
      String pkg,
      String fqn,
      String name,
      String modulePath,
      String framework,
      boolean isEnum,
      boolean isAbstract,
      String kind,
      int startLine,
      int endLine) {
    upsertClassNode(
        file,
        pkg,
        fqn,
        name,
        isAbstract,
        "",
        isEnum,
        false,
        false,
        JAVASCRIPT_LANGUAGE,
        kind,
        modulePath,
        framework);
  }
}
