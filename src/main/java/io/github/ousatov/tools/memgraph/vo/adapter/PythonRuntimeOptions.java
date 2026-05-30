package io.github.ousatov.tools.memgraph.vo.adapter;

import io.github.ousatov.tools.memgraph.exe.analyze.RuntimeMode;
import java.nio.file.Path;

/**
 * Python parser runtime settings.
 *
 * @author Oleksii Usatov
 */
public record PythonRuntimeOptions(
    Path cacheRoot, String pythonVersion, String pythonBuild, RuntimeMode runtimeMode) {}
