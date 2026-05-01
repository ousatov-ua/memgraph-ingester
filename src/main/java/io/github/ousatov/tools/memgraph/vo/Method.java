package io.github.ousatov.tools.memgraph.vo;

/**
 * Method
 *
 * @author Oleksii Usatov
 * @since 01.05.2026
 */
public record Method(
    String ownerFqn,
    String signature,
    String name,
    String returnType,
    boolean isStatic,
    String visibility,
    int startLine,
    int endLine,
    boolean isSynthetic) {}
