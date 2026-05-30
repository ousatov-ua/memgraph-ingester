package io.github.ousatov.tools.memgraph.vo.rag;

/**
 * Type source range used to build code RAG chunks.
 *
 * @author Oleksii Usatov
 */
public record TypeChunk(
    String sourceLabel,
    String sourceId,
    String ownerFqn,
    String name,
    String kind,
    int startLine,
    int endLine) {}
