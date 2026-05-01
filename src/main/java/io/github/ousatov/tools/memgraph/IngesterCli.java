package io.github.ousatov.tools.memgraph;

import io.github.ousatov.tools.memgraph.vo.Settings;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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
  @SuppressWarnings("unused")
  private Path sourceRoot;

  @Option(
      names = {"-b", "--bolt"},
      required = true,
      description = "Bolt URL, e.g. bolt://host:7687")
  @SuppressWarnings("unused")
  private String boltUrl;

  @Option(
      names = {"-u", "--user"},
      defaultValue = "")
  @SuppressWarnings("unused")
  private String user;

  @Option(
      names = {"-p", "--pass"},
      defaultValue = "")
  @SuppressWarnings("unused")
  private String pass;

  @Option(
      names = {"-P", "--project"},
      required = true,
      description =
          "Logical project name; namespaces all nodes so multiple "
              + "projects can share one Memgraph instance")
  @SuppressWarnings("unused")
  private String project;

  @Option(
      names = "--wipe-project-code",
      description = "Delete the code graph belonging to this project before ingesting")
  @SuppressWarnings("unused")
  private boolean wipeProjectCode;

  @Option(
      names = "--wipe-project-memories",
      description = "Delete the memory graph belonging to this project before ingesting")
  @SuppressWarnings("unused")
  private boolean wipeProjectMemories;

  @Option(
      names = {"-t", "--threads"},
      defaultValue = "1",
      description =
          "Number of parser threads. Each thread gets its own Bolt session. "
              + "Defaults to 1 (sequential). Values above the number of CPU cores rarely help "
              + "because Memgraph serializes writes internally.")
  @SuppressWarnings("unused")
  private int threads;

  @Option(
      names = {"--apply-schema"},
      description = "Apply schema on Memgraph")
  @SuppressWarnings("unused")
  private boolean applySchema;

  @Option(
      names = {"--wipe-all"},
      description = "Wipe all data from Memgraph")
  @SuppressWarnings("unused")
  private boolean wipeAllData;

  @Option(
      names = {"--incremental"},
      description = "Skip files whose last-modified timestamp matches the stored value")
  @SuppressWarnings("unused")
  private boolean incremental;

  @Option(
      names = {"--classpath"},
      defaultValue = "",
      description =
          "Additional classpath entries (JARs) for symbol resolution, separated by "
              + "the platform path separator. Improves CALLS edge and type resolution coverage.")
  @SuppressWarnings("unused")
  private String classpath;

  /** Entry point. */
  public static void main(String[] args) {
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
      List<Path> cpEntries =
          (classpath == null || classpath.isBlank())
              ? List.of()
              : Arrays.stream(classpath.split(File.pathSeparator))
                  .map(Path::of)
                  .filter(Files::isRegularFile)
                  .toList();
      ParseService parseService = new ParseService(sourceRoot, cpEntries);
      IngestionOrchestrator orchestrator =
          new IngestionOrchestrator(sourceRoot, project, threads, driver, parseService);
      var settings =
          new Settings(wipeAllData, applySchema, wipeProjectCode, wipeProjectMemories, incremental);
      int failures = orchestrator.run(settings);
      if (failures > 0) {
        log.error("Ingestion finished with {} file failure(s).", failures);
        return 2;
      }
    }
    log.info("Ingestion complete for project '{}'.", project);
    return 0;
  }
}
