package io.github.ousatov.tools.memgraph.vo.analysis.module;

/**
 * Common module type relation value object.
 *
 * @author Oleksii Usatov
 */
public record RelationDecl(String kind, String childFqn, String targetFqn) {}
