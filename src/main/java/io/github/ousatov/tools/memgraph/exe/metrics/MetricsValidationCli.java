package io.github.ousatov.tools.memgraph.exe.metrics;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.schema.MemgraphDriver;
import java.io.IOException;
import java.nio.file.Path;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

/**
 * Maven-invoked entry point for validating current graph metrics against a snapshot file.
 *
 * @author Oleksii Usatov
 */
public final class MetricsValidationCli {

  private static final String DEFAULT_BOLT_URL =
      AppConfig.stringValue("metrics.validation.bolt-url");
  private static final String DEFAULT_PROJECT = AppConfig.stringValue("metrics.validation.project");

  private MetricsValidationCli() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Entry point used by the Maven exec plugin. */
  public static void main(String[] args) {
    System.exit(run(args));
  }

  @SuppressWarnings(Const.Warnings.STANDARD_OUTPUT)
  static int run(String[] args) {
    try {
      Path expectedFile = expectedFile(args);
      String boltUrl = System.getProperty("metrics.bolt", DEFAULT_BOLT_URL);
      String project = System.getProperty("metrics.project", DEFAULT_PROJECT);
      String user = System.getProperty("metrics.user", Const.Symbols.EMPTY);
      String pass = System.getProperty("metrics.pass", Const.Symbols.EMPTY);
      try (Driver driver = MemgraphDriver.open(boltUrl, user, pass);
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
    String expected =
        args.length > 0 ? args[0] : System.getProperty("metrics.expected", Const.Symbols.EMPTY);
    if (expected == null || expected.isBlank() || expected.startsWith("${")) {
      throw new IllegalArgumentException("Provide -Dmetrics.expected=/path/to/expected-metrics.md");
    }
    return Path.of(expected);
  }
}
