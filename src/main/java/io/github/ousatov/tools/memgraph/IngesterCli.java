package io.github.ousatov.tools.memgraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI entry point. Parses arguments and delegates to {@link IngestionOrchestrator}.
 *
 * <p>Exit codes: {@code 0} = success, {@code 1} = invalid arguments, {@code 2} = one or more files
 * failed to parse or ingest.
 *
 * @author Oleksii Usatov
 */
@Command(name = "ingest", mixinStandardHelpOptions = true, version = "1.0")
public final class IngesterCli implements Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(IngesterCli.class);

  @Option(
      names = {"-s", "--source"},
      required = true,
      description = "Root source directory (e.g. src/main/java)")
  private Path sourceRoot;

  @Option(
      names = {"-b", "--bolt"},
      required = true,
      description = "Bolt URL, e.g. bolt://host:7687")
  private String boltUrl;

  @Option(
      names = {"-u", "--user"},
      defaultValue = "")
  private String user;

  @Option(
      names = {"-p", "--pass"},
      defaultValue = "")
  private String pass;

  @Option(
      names = {"-P", "--project"},
      required = true,
      description =
          "Logical project name; namespaces all nodes so multiple "
              + "projects can share one Memgraph instance")
  private String project;

  @Option(
      names = "--wipe-project",
      description = "Delete all nodes belonging to this project before ingesting")
  private boolean wipe;

  @Option(
      names = {"-t", "--threads"},
      defaultValue = "1",
      description =
          "Number of parser threads. Each thread gets its own Bolt session. "
              + "Defaults to 1 (sequential). Values above the number of CPU cores rarely help "
              + "because Memgraph serializes writes internally.")
  private int threads;

  @Option(
      names = {"--apply-schema"},
      description = "Apply schema on Memgraph")
  private boolean applySchema;

  @Option(
      names = {"--wipe-all"},
      description = "Wipe all data from Memgraph")
  private boolean wipeAllData;

  /** Entry point. */
  static void main(String[] args) {
    int exit = new CommandLine(new IngesterCli()).execute(args);
    System.exit(exit);
  }

  @Override
  public Integer call() {
    if (threads < 1) {
      log.error("--threads must be >= 1 (got {})", threads);
      return 1;
    }
    if (!Files.isDirectory(sourceRoot)) {
      log.error("--source must be an existing directory: {}", sourceRoot);
      return 1;
    }
    try (Driver driver = GraphDatabase.driver(boltUrl, AuthTokens.basic(user, pass))) {
      ParseService parseService = new ParseService(sourceRoot);
      IngestionOrchestrator orchestrator =
          new IngestionOrchestrator(sourceRoot, project, threads, driver, parseService);
      int failures = orchestrator.run(wipeAllData, applySchema, wipe);
      if (failures > 0) {
        log.error("Ingestion finished with {} file failure(s).", failures);
        return 2;
      }
    }
    log.info("Ingestion complete for project '{}'.", project);
    return 0;
  }
}
