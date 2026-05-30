package io.github.ousatov.tools.memgraph.vo.analysis.module;

/**
 * Common module annotation or decorator value object.
 *
 * @author Oleksii Usatov
 */
public record AnnotationDecl(String ownerKind, String ownerKey, String fqn, String name) {}
