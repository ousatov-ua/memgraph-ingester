package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.analyze.JsAnalysis;
import io.github.ousatov.tools.memgraph.exe.analyze.ModuleAnalysis;

/**
 * Builds derived {@code :CodeChunk} rows from JavaScript/TypeScript analysis.
 *
 * @author Oleksii Usatov
 */
public final class JsCodeChunkBuilder extends ModuleCodeChunkBuilder<JsAnalysis> {

  public JsCodeChunkBuilder() {
    super(SourceLanguage.JAVASCRIPT.graphName(), JsCodeChunkBuilder::typeLabel);
  }

  private static String typeLabel(ModuleAnalysis.TypeDecl type) {
    return Params.INTERFACE.equals(type.kind()) || Params.TYPE.equals(type.kind())
        ? Labels.INTERFACE
        : Labels.CLASS;
  }
}
