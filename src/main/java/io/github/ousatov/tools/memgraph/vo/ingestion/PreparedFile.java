package io.github.ousatov.tools.memgraph.vo.ingestion;

import java.nio.file.Path;

/**
 * Prepared source file outcome.
 *
 * @author Oleksii Usatov
 */
public interface PreparedFile {

  Path path();

  boolean success();

  boolean writeRequired();
}
