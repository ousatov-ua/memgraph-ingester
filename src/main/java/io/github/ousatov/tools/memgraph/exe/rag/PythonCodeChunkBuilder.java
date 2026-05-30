package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalysis;

/**
 * Builds derived {@code :CodeChunk} rows from Python analysis.
 *
 * @author Oleksii Usatov
 */
public final class PythonCodeChunkBuilder extends ModuleCodeChunkBuilder<PythonAnalysis> {

  public PythonCodeChunkBuilder() {
    super(SourceLanguage.PYTHON.graphName(), PythonCodeChunkBuilder::typeLabel);
  }

  private static String typeLabel(
      io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl type) {
    return Labels.CLASS;
  }
}
