package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ousatov.tools.memgraph.extension.MemgraphExtension;
import io.github.ousatov.tools.memgraph.extension.MemgraphInstance;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import picocli.CommandLine;

/**
 * CLI tests for argument validation and end-to-end exit codes.
 *
 * @author Oleksii Usatov
 */
@ExtendWith(MemgraphExtension.class)
class IngesterCliTest {

  @Test
  void rejectsInvalidThreadCountBeforeOpeningDriver() throws IOException {
    Path sourceDir = Files.createTempDirectory("cli-invalid-threads-");
    try {
      int exitCode =
          new CommandLine(new IngesterCli())
              .execute(
                  "-s",
                  sourceDir.toString(),
                  "-b",
                  "bolt://127.0.0.1:1",
                  "-P",
                  "cli-invalid-threads",
                  "--threads",
                  "0");

      assertEquals(1, exitCode);
    } finally {
      deleteDir(sourceDir);
    }
  }

  @Test
  void rejectsMissingSourceBeforeOpeningDriver() {
    Path missing = Path.of("target/missing-" + UUID.randomUUID());

    int exitCode =
        new CommandLine(new IngesterCli())
            .execute(
                "-s", missing.toString(), "-b", "bolt://127.0.0.1:1", "-P", "cli-missing-source");

    assertEquals(1, exitCode);
  }

  @Test
  void returnsZeroForSuccessfulIngestionWithClasspath(MemgraphInstance mg) throws IOException {
    Path sourceDir = Files.createTempDirectory("cli-success-");
    String project = "cli-success-" + UUID.randomUUID();
    try {
      Files.writeString(sourceDir.resolve("Good.java"), "public class Good { void ok() {} }");
      Path classpathEntry = Files.writeString(sourceDir.resolve("not-a-real.jar"), "bad jar");
      Path missingClasspathEntry = sourceDir.resolve("missing.jar");

      int exitCode =
          new CommandLine(new IngesterCli())
              .execute(
                  "-s",
                  sourceDir.toString(),
                  "-b",
                  mg.getBoltUrl(),
                  "-P",
                  project,
                  "--apply-schema",
                  "--classpath",
                  classpathEntry + java.io.File.pathSeparator + missingClasspathEntry);

      assertEquals(0, exitCode);
    } finally {
      wipeProject(mg, project);
      deleteDir(sourceDir);
    }
  }

  @Test
  void returnsTwoWhenAnyFileFailsToParse(MemgraphInstance mg) throws IOException {
    Path sourceDir = Files.createTempDirectory("cli-parse-failure-");
    String project = "cli-parse-failure-" + UUID.randomUUID();
    try {
      Files.writeString(sourceDir.resolve("Bad.java"), "this is not valid java");

      int exitCode =
          new CommandLine(new IngesterCli())
              .execute(
                  "-s",
                  sourceDir.toString(),
                  "-b",
                  mg.getBoltUrl(),
                  "-P",
                  project,
                  "--apply-schema");

      assertEquals(2, exitCode);
    } finally {
      wipeProject(mg, project);
      deleteDir(sourceDir);
    }
  }

  private static void wipeProject(MemgraphInstance mg, String project) {
    try (var driver = GraphDatabase.driver(mg.getBoltUrl(), AuthTokens.basic("", ""));
        var session = driver.session()) {
      session.run("MATCH (n) WHERE n.project = $p DETACH DELETE n", Map.of("p", project)).consume();
      session.run("MATCH (p:Project {name: $p}) DETACH DELETE p", Map.of("p", project)).consume();
    }
  }

  private static void deleteDir(Path dir) throws IOException {
    if (dir != null && Files.exists(dir)) {
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
      }
    }
  }
}
