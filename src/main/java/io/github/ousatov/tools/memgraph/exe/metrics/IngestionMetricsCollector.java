package io.github.ousatov.tools.memgraph.exe.metrics;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.vo.metrics.IngestionMetricRow;
import io.github.ousatov.tools.memgraph.vo.metrics.MetricQuery;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Session;

/**
 * Collects read-only graph counts after ingestion cleanup has finished.
 *
 * @author Oleksii Usatov
 */
public final class IngestionMetricsCollector {

  private static final String VALUE = Const.Params.VALUE;
  private static final String RESOURCE_BASE = "/io/github/ousatov/tools/memgraph/cypher/metrics/";

  private static final List<MetricQuery> QUERIES =
      List.of(
          query("files", "files.cypher"),
          query("packages", "packages.cypher"),
          query("classes.internal", "classes-internal.cypher"),
          query("classes.external", "classes-external.cypher"),
          query("interfaces", "interfaces.cypher"),
          query("annotations", "annotations.cypher"),
          query("methods", "methods.cypher"),
          query("methods.synthetic", "methods-synthetic.cypher"),
          query("fields", "fields.cypher"),
          query("calls", "calls.cypher"),
          query("extends", "extends.cypher"),
          query(Params.IMPLEMENTS, "implements.cypher"),
          query("annotated_with", "annotated-with.cypher"),
          query("pending_calls", "pending-calls.cypher"),
          query("pending_calls.name_only", "pending-calls-name-only.cypher"),
          query("resolved_code_refs", "resolved-code-refs.cypher"));

  private IngestionMetricsCollector() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Collects a project-scoped metrics snapshot using the provided session. */
  public static IngestionMetrics collect(Session session, String project) {
    Map<String, Object> params = Map.of(Labels.PROJECT, project);
    List<IngestionMetricRow> rows =
        QUERIES.stream()
            .map(query -> new IngestionMetricRow(query.name(), count(session, query, params)))
            .toList();
    return new IngestionMetrics(rows);
  }

  static List<String> resourceNames() {
    return QUERIES.stream().map(MetricQuery::resourceName).toList();
  }

  private static long count(Session session, MetricQuery query, Map<String, Object> params) {
    return session.run(query.cypher(), params).single().get(VALUE).asLong();
  }

  private static MetricQuery query(String name, String resourceName) {
    return new MetricQuery(name, resourceName, load(resourceName));
  }

  private static String load(String resourceName) {
    String resource = RESOURCE_BASE + resourceName;
    try (InputStream in = IngestionMetricsCollector.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new ProcessingException(resource + " is missing from jar");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ProcessingException(resource + " could not be loaded from jar", e);
    }
  }
}
