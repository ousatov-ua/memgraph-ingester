package io.github.ousatov.tools.memgraph.vo.metrics;

/**
 * Metric query descriptor.
 *
 * @author Oleksii Usatov
 */
public record MetricQuery(String name, String resourceName, String cypher) {}
