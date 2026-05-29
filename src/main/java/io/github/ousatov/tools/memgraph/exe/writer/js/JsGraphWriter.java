package io.github.ousatov.tools.memgraph.exe.writer.js;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.writer.ModuleGraphWriter;
import java.nio.file.Path;

/**
 * Writes JavaScript/TypeScript-specific graph structures.
 *
 * @author Oleksii Usatov
 */
public final class JsGraphWriter extends ModuleGraphWriter {

  public JsGraphWriter(Dependencies dependencies) {
    super(dependencies, JAVASCRIPT_LANGUAGE);
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
        file,
        pkg,
        fqn,
        name,
        modulePath,
        Const.Symbols.EMPTY,
        true,
        false,
        Params.ENUM,
        startLine,
        endLine);
  }

  /** Writes a JavaScript/TypeScript class {@code EXTENDS} relation. */
  @Override
  public void upsertExtendsClass(String childFqn, String parentFqn) {
    upsertClassExtends(childFqn, parentFqn, languageGraphName());
  }

  /** Writes a JavaScript/TypeScript interface {@code EXTENDS} relation. */
  public void upsertInterfaceExtends(String childFqn, String parentFqn) {
    upsertInterfaceExtends(childFqn, parentFqn, languageGraphName());
  }

  /** Writes a JavaScript/TypeScript class {@code IMPLEMENTS} relation. */
  public void upsertImplements(String childFqn, String interfaceFqn) {
    upsertImplements(childFqn, interfaceFqn, languageGraphName());
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
        file,
        pkg,
        fqn,
        name,
        true,
        Const.Symbols.EMPTY,
        languageGraphName(),
        kind,
        modulePath,
        framework);
  }
}
