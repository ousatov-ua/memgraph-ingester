package io.github.ousatov.tools.memgraph.exe.metrics;

import java.io.IOException;
import java.nio.file.Path;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

/**
 * Maven-invoked entry point for validating current graph metrics against a snapshot file.
 *
 * @author Oleksii Usatov
 */
public final class MetricsValidationCli {

  private static final String DEFAULT_BOLT_URL = "bolt://localhost:7687";
  private static final String DEFAULT_PROJECT = "memgraph-ingester";

  private MetricsValidationCli() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Entry point used by the Maven exec plugin. */
  public static void main(String[] args) {
    System.exit(run(args));
  }

  @SuppressWarnings("java:S106")
  static int run(String[] args) {
    try {
      Path expectedFile = expectedFile(args);
      String boltUrl = System.getProperty("metrics.bolt", DEFAULT_BOLT_URL);
      String project = System.getProperty("metrics.project", DEFAULT_PROJECT);
      String user = System.getProperty("metrics.user", "");
      String pass = System.getProperty("metrics.pass", "");
      try (Driver driver = GraphDatabase.driver(boltUrl, AuthTokens.basic(user, pass));
          Session session = driver.session()) {
        String actualMetrics =
            IngestionMetricsCollector.collect(session, project).toMarkdownTable();
        MetricsSnapshotValidator.validate(expectedFile, actualMetrics);
      }
      System.out.printf("Metrics match %s for project '%s'.%n", expectedFile, project);
      return 0;
    } catch (IOException | RuntimeException e) {
      System.err.println("Metrics validation failed: " + e.getMessage());
      return 1;
    }
  }

  private static Path expectedFile(String[] args) {
    String expected = args.length > 0 ? args[0] : System.getProperty("metrics.expected", "");
    if (expected == null || expected.isBlank() || expected.startsWith("${")) {
      throw new IllegalArgumentException("Provide -Dmetrics.expected=/path/to/expected-metrics.md");
    }
    return Path.of(expected);
  }
}
