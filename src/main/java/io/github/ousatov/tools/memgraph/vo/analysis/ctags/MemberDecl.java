package io.github.ousatov.tools.memgraph.vo.analysis.ctags;

import io.github.ousatov.tools.memgraph.def.Const;

/**
 * Ctags member declaration value object.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
public record MemberDecl(
    String ownerFqn,
    String memberType,
    String graphKind,
    String fqnOrSignature,
    String name,
    String dataType,
    boolean isStatic,
    String visibility,
    int startLine,
    int endLine) {}
