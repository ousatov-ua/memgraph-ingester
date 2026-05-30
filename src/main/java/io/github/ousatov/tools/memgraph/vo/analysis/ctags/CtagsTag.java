package io.github.ousatov.tools.memgraph.vo.analysis.ctags;

import io.github.ousatov.tools.memgraph.def.Const;

/**
 * Parsed Universal Ctags JSON tag.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
public record CtagsTag(
    String name,
    String kind,
    String scope,
    String scopeKind,
    String signature,
    String typeref,
    String access,
    boolean isStatic,
    int line,
    int endLine) {}
