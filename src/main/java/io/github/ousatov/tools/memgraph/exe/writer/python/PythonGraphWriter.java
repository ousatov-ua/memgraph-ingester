package io.github.ousatov.tools.memgraph.exe.writer.python;

import io.github.ousatov.tools.memgraph.exe.writer.ModuleGraphWriter;

/**
 * Writes Python-specific graph structures.
 *
 * @author Oleksii Usatov
 */
public final class PythonGraphWriter extends ModuleGraphWriter {

  public PythonGraphWriter(Dependencies dependencies) {
    super(dependencies, PYTHON_LANGUAGE);
  }
}
