package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.FieldWrite;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Base writer for module-oriented languages that model top-level declarations through a synthetic
 * owner class.
 *
 * @author Oleksii Usatov
 */
public abstract class ModuleGraphWriter extends CommonGraphWriter {

  private final String languageGraphName;

  protected ModuleGraphWriter(Dependencies dependencies, String languageGraphName) {
    super(dependencies);
    this.languageGraphName = languageGraphName;
  }

  /** Upserts the synthetic module owner used for top-level declarations. */
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
        Const.Symbols.EMPTY,
        false,
        false,
        false,
        languageGraphName,
        Params.MODULE,
        modulePath,
        Const.Symbols.EMPTY);
    upsertMethodNode(
        file,
        new Method(
            fqn,
            fqn + Const.Symbols.DOT + Labels.INIT + Const.Symbols.PARENS,
            Labels.INIT,
            Labels.VOID,
            true,
            Const.Symbols.EMPTY,
            startLine,
            endLine,
            true,
            languageGraphName,
            Params.MODULE));
  }

  /** Upserts a class declaration using the existing {@code :Class} label. */
  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
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
              fqn + Const.Symbols.DOT + Labels.INIT + Const.Symbols.PARENS,
              Labels.INIT,
              Labels.VOID,
              false,
              Const.Symbols.EMPTY,
              startLine,
              endLine,
              true,
              languageGraphName,
              Params.CONSTRUCTOR));
    }
  }

  /** Writes a class {@code EXTENDS} relation. */
  public void upsertExtendsClass(String childFqn, String parentFqn) {
    upsertTypeRelation(
        Cypher.CYPHER_UPSERT_EXTENDS_CLASS,
        childFqn,
        parentFqn,
        Params.PARENT,
        Params.PARENT_NAME,
        Params.PARENT_PKG,
        languageGraphName);
  }

  /** Upserts prebuilt members in batches. */
  public void upsertMembers(Path file, Collection<FieldWrite> fields, Collection<Method> methods) {
    upsertFieldNodes(file, fields);
    upsertMethodNodes(file, methods);
  }

  /** Upserts a field. */
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
            new FieldWrite(
                ownerFqn,
                fqn,
                name,
                type,
                isStatic,
                Const.Symbols.EMPTY,
                languageGraphName,
                kind)));
  }

  /** Upserts a function or method. */
  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
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
            Const.Symbols.EMPTY,
            startLine,
            endLine,
            false,
            languageGraphName,
            kind));
  }

  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
  protected void upsertTypeClass(
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
        Const.Symbols.EMPTY,
        isEnum,
        false,
        false,
        languageGraphName,
        kind,
        modulePath,
        framework);
  }

  protected final String languageGraphName() {
    return languageGraphName;
  }
}
