package io.github.ousatov.tools.memgraph.vo.ingestion;

import java.nio.file.Path;
import java.util.List;

/**
 * Source files and retained paths captured from one watch-cycle snapshot.
 *
 * @author Oleksii Usatov
 */
public record WatchSourceSnapshot(List<SourceFile> files, List<Path> retainedPaths) {}
