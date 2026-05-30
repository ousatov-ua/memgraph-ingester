package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.vo.rag.CodeChunkAnalysis;

/** Converts parser-owned source analysis into normalized CodeChunk analysis. */
@FunctionalInterface
public interface CodeChunkAnalyzer<T> {

  CodeChunkAnalysis analyze(T parsed);
}
