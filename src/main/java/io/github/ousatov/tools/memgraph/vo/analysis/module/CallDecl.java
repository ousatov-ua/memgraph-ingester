package io.github.ousatov.tools.memgraph.vo.analysis.module;

/**
 * Common module call edge value object.
 *
 * @author Oleksii Usatov
 */
public record CallDecl(
    String callerSignature, String calleeSignature, String calleeOwnerFqn, String calleeName) {}
