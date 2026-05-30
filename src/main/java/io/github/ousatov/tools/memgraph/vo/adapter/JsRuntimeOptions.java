package io.github.ousatov.tools.memgraph.vo.adapter;

import io.github.ousatov.tools.memgraph.exe.analyze.RuntimeMode;
import java.nio.file.Path;

/**
 * JavaScript parser runtime settings.
 *
 * @author Oleksii Usatov
 */
public record JsRuntimeOptions(
    Path cacheRoot, String nodeVersion, String typescriptVersion, RuntimeMode runtimeMode) {}
