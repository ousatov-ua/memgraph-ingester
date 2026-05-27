package io.github.ousatov.tools.memgraph.exe.rag;

/** Converts parser-owned source analysis into normalized CodeChunk analysis. */
@FunctionalInterface
public interface CodeChunkAnalyzer<T> {

  CodeChunkAnalysis analyze(T parsed);
}
