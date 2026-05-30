package io.github.ousatov.tools.memgraph.vo.analysis.ctags;

import io.github.ousatov.tools.memgraph.def.Const;

/**
 * Ctags type declaration value object.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
public record TypeDecl(
    String graphKind,
    String rawKind,
    String fqn,
    String name,
    boolean interfaceLike,
    int startLine,
    int endLine) {}
