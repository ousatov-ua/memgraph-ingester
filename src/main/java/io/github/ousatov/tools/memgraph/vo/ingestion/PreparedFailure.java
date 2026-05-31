package io.github.ousatov.tools.memgraph.vo.ingestion;

import java.nio.file.Path;

/**
 * Prepared source file whose parse phase failed; no graph writes should be attempted.
 *
 * @author Oleksii Usatov
 */
public record PreparedFailure(Path path) implements PreparedFile {}
