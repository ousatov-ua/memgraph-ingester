package io.github.ousatov.tools.memgraph.vo.analysis.ctags;

/**
 * Ctags process execution result.
 *
 * @author Oleksii Usatov
 */
public record ProcessResult(int exitCode, String stdout, String stderr) {}
