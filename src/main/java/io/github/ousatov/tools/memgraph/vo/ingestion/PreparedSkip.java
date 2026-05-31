package io.github.ousatov.tools.memgraph.vo.ingestion;

import java.nio.file.Path;

/**
 * Prepared source file that does not require graph writes (incremental no-op).
 *
 * @author Oleksii Usatov
 */
public record PreparedSkip(Path path) implements PreparedFile {}
