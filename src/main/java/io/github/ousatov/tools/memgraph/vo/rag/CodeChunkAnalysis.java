package io.github.ousatov.tools.memgraph.vo.rag;

import java.util.List;

/**
 * Normalized source structure used by common CodeChunk generation.
 *
 * @author Oleksii Usatov
 */
public record CodeChunkAnalysis(
    String language,
    String moduleFqn,
    String moduleName,
    int startLine,
    int endLine,
    List<TypeChunk> types,
    List<MemberChunk> members) {}
