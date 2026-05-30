package io.github.ousatov.tools.memgraph.vo.adapter;

import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.nio.file.Path;

/**
 * Universal Ctags parser runtime settings.
 *
 * @author Oleksii Usatov
 */
public record CtagsRuntimeOptions(Path cacheRoot, String ctagsVersion, RuntimeMode runtimeMode) {}
