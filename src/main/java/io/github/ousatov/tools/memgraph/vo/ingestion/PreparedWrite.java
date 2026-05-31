package io.github.ousatov.tools.memgraph.vo.ingestion;

import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import java.nio.file.Path;

/**
 * Prepared source file whose parse phase succeeded; graph writes are required.
 *
 * @author Oleksii Usatov
 */
public record PreparedWrite<T>(
    Path path, LanguageAdapter<T> adapter, T parsed, SourceFileDefinitions definitions)
    implements PreparedFile {}
