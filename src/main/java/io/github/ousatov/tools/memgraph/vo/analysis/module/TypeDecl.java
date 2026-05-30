package io.github.ousatov.tools.memgraph.vo.analysis.module;

import io.github.ousatov.tools.memgraph.def.Const;

/**
 * Common module type declaration value object.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
public record TypeDecl(
    String kind,
    String fqn,
    String name,
    String framework,
    boolean hasConstructor,
    boolean isAbstract,
    int startLine,
    int endLine) {}
