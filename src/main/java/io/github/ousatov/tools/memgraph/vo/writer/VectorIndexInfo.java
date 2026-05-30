package io.github.ousatov.tools.memgraph.vo.writer;

/**
 * Vector index configuration read from Memgraph.
 *
 * @author Oleksii Usatov
 */
public record VectorIndexInfo(
    String label, String property, int dimension, int capacity, String metric, String scalarKind) {}
