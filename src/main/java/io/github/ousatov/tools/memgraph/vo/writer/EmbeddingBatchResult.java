package io.github.ousatov.tools.memgraph.vo.writer;

import java.util.List;

/**
 * Result of one embedding refresh batch.
 *
 * @author Oleksii Usatov
 */
public record EmbeddingBatchResult(boolean success, List<String> ids) {}
