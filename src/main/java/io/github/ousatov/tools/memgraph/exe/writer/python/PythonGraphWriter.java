package io.github.ousatov.tools.memgraph.exe.writer.python;

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
 * Writes Python-specific graph structures.
 *
 * @author Oleksii Usatov
 */
public final class PythonGraphWriter extends CommonGraphWriter {

  public PythonGraphWriter(Dependencies dependencies) {
    super(dependencies);
  }

  /** Upserts the synthetic module owner used for top-level Python declarations. */
  public void upsertModule(
      Path file,
      String pkg,
      String fqn,
      String name,
      String modulePath,
      int startLine,
      int endLine) {
    upsertClassNode(
        file, pkg, fqn, name, false, "", false, false, false, PYTHON_LANGUAGE, "module", modulePath, "");
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
            PYTHON_LANGUAGE,
            "module"));
  }

  /** Upserts a Python class declaration using the existing {@code :Class} label. */
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
    upsertClassNode(
        file, pkg, fqn, name, isAbstract, "", false, false, false, PYTHON_LANGUAGE, Params.CLASS, modulePath, framework);
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
              PYTHON_LANGUAGE,
              "constructor"));
    }
  }

  /** Writes a Python class {@code EXTENDS} relation. */
  public void upsertExtendsClass(String childFqn, String parentFqn) {
    upsertTypeRelation(
        Cypher.CYPHER_UPSERT_EXTENDS_CLASS,
        childFqn,
        parentFqn,
        Params.PARENT,
        Params.PARENT_NAME,
        Params.PARENT_PKG,
        PYTHON_LANGUAGE);
  }

  /** Upserts prebuilt Python members in batches. */
  public void upsertMembers(Path file, Collection<FieldWrite> fields, Collection<Method> methods) {
    upsertFieldNodes(file, fields);
    upsertMethodNodes(file, methods);
  }

  /** Upserts a Python field. */
  public void upsertField(
      Path file,
      String ownerFqn,
      String fqn,
      String name,
      String type,
      boolean isStatic,
      String kind) {
    upsertFieldNodes(
        file, List.of(new FieldWrite(ownerFqn, fqn, name, type, isStatic, "", PYTHON_LANGUAGE, kind)));
  }

  /** Upserts a Python function or method. */
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
            PYTHON_LANGUAGE,
            kind));
  }
}
