package io.github.ousatov.tools.memgraph.vo.cli;

import java.nio.file.Path;

/**
 * Agent instruction installation result.
 *
 * @author Oleksii Usatov
 */
public record InstallResult(Path target, boolean includeMemories) {}
