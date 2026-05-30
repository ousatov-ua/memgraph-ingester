package io.github.ousatov.tools.memgraph.vo.schema;

import java.util.List;

/**
 * Memgraph uniqueness constraint metadata.
 *
 * @author Oleksii Usatov
 */
public record ConstraintInfo(String label, List<String> properties) {}
