package io.github.ousatov.tools.memgraph.vo.ingestion;

import java.nio.file.Path;

/**
 * Outcome of the prepare phase for one source file.
 *
 * <p>The three permitted subtypes cover every outcome: {@link PreparedFailure} (parse failed),
 * {@link PreparedSkip} (no re-ingest needed), and {@link PreparedWrite} (parse succeeded and graph
 * writes are required). Callers should switch exhaustively over the sealed hierarchy rather than
 * inspecting boolean flags.
 *
 * @author Oleksii Usatov
 */
public sealed interface PreparedFile permits PreparedFailure, PreparedSkip, PreparedWrite {

  Path path();
}
