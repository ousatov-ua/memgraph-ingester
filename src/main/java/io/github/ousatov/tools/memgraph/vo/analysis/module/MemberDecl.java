package io.github.ousatov.tools.memgraph.vo.analysis.module;

import io.github.ousatov.tools.memgraph.def.Const;

/**
 * Common module member declaration value object.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
public record MemberDecl(
    String ownerFqn,
    String memberType,
    String kind,
    String key,
    String name,
    String dataType,
    boolean isStatic,
    int startLine,
    int endLine) {}
