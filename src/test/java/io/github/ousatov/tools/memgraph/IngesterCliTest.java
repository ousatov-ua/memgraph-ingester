package io.github.ousatov.tools.memgraph;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exe.analyze.ManagedPythonRuntime;
import io.github.ousatov.tools.memgraph.extension.MemgraphExtension;
import io.github.ousatov.tools.memgraph.extension.MemgraphInstance;
import io.github.ousatov.tools.memgraph.schema.MemgraphDriver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  void rejectsInvalidCodeEmbeddingBatchSizeBeforeOpeningDriver() throws IOException {
    Path sourceDir = Files.createTempDirectory("cli-invalid-code-embedding-batch-");
    try {
      int exitCode =
          new CommandLine(new IngesterCli())
              .execute(
                  "-s",
                  sourceDir.toString(),
                  "-b",
                  "bolt://127.0.0.1:1",
                  "-P",
                  "cli-invalid-code-embedding-batch",
                  "--code-embeddings",
                  "--code-embedding-batch-size=-1");

      assertEquals(1, exitCode);
    } finally {
      deleteDir(sourceDir);
    }
  }

  @Test
  void embeddingCliOptionsUseFixedCodeModelAndMemoryGate() {
    CommandLine commandLine = new CommandLine(new IngesterCli());
    var options = commandLine.getCommandSpec().optionsMap();

    assertEquals("true", options.get("--code-embeddings").defaultValue());
    assertEquals("true", options.get("--memory-embeddings").defaultValue());
    assertTrue(options.containsKey("--with-memories"));
    assertNull(options.get("--code-embedding-index"));
    assertNull(options.get("--code-embedding-model"));
    assertNull(options.get("--code-embedding-dimensions"));
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
  void rejectsOmittedSourceBeforeOpeningDriver() {
    int exitCode =
        new CommandLine(new IngesterCli())
            .execute("-b", "bolt://127.0.0.1:1", "-P", "cli-omitted-source");

    assertEquals(1, exitCode);
  }

  @Test
  void rejectsMissingBoltBeforeOpeningDriver() throws IOException {
    Path sourceDir = Files.createTempDirectory("cli-missing-bolt-");
    try {
      int exitCode =
          new CommandLine(new IngesterCli())
              .execute("-s", sourceDir.toString(), "-P", "cli-missing-bolt");

      assertEquals(1, exitCode);
    } finally {
      deleteDir(sourceDir);
    }
  }

  @Test
  void rejectsMissingProjectBeforeOpeningDriver() throws IOException {
    Path sourceDir = Files.createTempDirectory("cli-missing-project-");
    try {
      int exitCode =
          new CommandLine(new IngesterCli())
              .execute("-s", sourceDir.toString(), "-b", "bolt://127.0.0.1:1");

      assertEquals(1, exitCode);
    } finally {
      deleteDir(sourceDir);
    }
  }

  @Test
  void rejectsUnsupportedJsRuntimeModeBeforeOpeningDriver() throws IOException {
    Path sourceDir = Files.createTempDirectory("cli-invalid-js-runtime-");
    try {
      int exitCode =
          new CommandLine(new IngesterCli())
              .execute(
                  "-s",
                  sourceDir.toString(),
                  "-b",
                  "bolt://127.0.0.1:1",
                  "-P",
                  "cli-invalid-js-runtime",
                  "--js-runtime-mode",
                  "manual");

      assertEquals(1, exitCode);
    } finally {
      deleteDir(sourceDir);
    }
  }

  @Test
  void rejectsUnsupportedPythonRuntimeModeBeforeOpeningDriver() throws IOException {
    Path sourceDir = Files.createTempDirectory("cli-invalid-python-runtime-");
    try {
      int exitCode =
          new CommandLine(new IngesterCli())
              .execute(
                  "-s",
                  sourceDir.toString(),
                  "-b",
                  "bolt://127.0.0.1:1",
                  "-P",
                  "cli-invalid-python-runtime",
                  "--python-runtime-mode",
                  "manual");

      assertEquals(1, exitCode);
    } finally {
      deleteDir(sourceDir);
    }
  }

  @Test
  void pythonRuntimeCacheDefaultsIndependentlyOfJsRuntimeCache() throws IOException {
    Path explicitPythonCache = Files.createTempDirectory("cli-python-cache-");
    try {
      assertEquals(
          ManagedPythonRuntime.defaultCacheRoot(), IngesterCli.resolvePythonRuntimeCache(null));
      assertEquals(explicitPythonCache, IngesterCli.resolvePythonRuntimeCache(explicitPythonCache));
    } finally {
      deleteDir(explicitPythonCache);
    }
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
  void installsInstructionsAndContinuesIntoWatchIngestion(MemgraphInstance mg) throws Exception {
    Path sourceDir = Files.createTempDirectory("cli-watch-instructions-");
    Path instructions = sourceDir.resolve("AGENTS.md");
    String project = "cli-watch-instructions-" + UUID.randomUUID();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicInteger exitCode = new AtomicInteger(-1);
    Thread worker =
        new Thread(
            () -> {
              try {
                int result =
                    new CommandLine(new IngesterCli())
                        .execute(
                            "-s",
                            sourceDir.toString(),
                            "-b",
                            mg.getBoltUrl(),
                            "-P",
                            project,
                            "--instructions-file",
                            instructions.toString(),
                            "--with-memories",
                            "--apply-schema",
                            "--watch");
                exitCode.set(result);
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "cli-watch-instructions-test");
    try {
      Files.writeString(sourceDir.resolve("Good.java"), "public class Good { void ok() {} }");
      worker.start();

      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(
              () -> {
                assertTrue(
                    Files.readString(instructions).contains("## Memories"),
                    "instructions should be installed before watch mode blocks");
                assertTrue(
                    classExists(mg, project, "Good"),
                    "watch mode should still perform the initial ingestion");
              });
    } finally {
      worker.interrupt();
      worker.join(TimeUnit.SECONDS.toMillis(5));
      wipeProject(mg, project);
      deleteDir(sourceDir);
    }

    assertFalse(worker.isAlive(), "CLI watch mode must exit after interruption");
    assertNull(failure.get(), () -> "CLI watch mode failed: " + failure.get());
    assertEquals(0, exitCode.get());
  }

  @Test
  void withMemoriesAppliesDefaultInstructionsAndContinuesIntoWatchIngestion(MemgraphInstance mg)
      throws Exception {
    Path workDir = Files.createTempDirectory("cli-watch-default-instructions-");
    Path sourceDir = Files.createDirectories(workDir.resolve("src"));
    Path output = workDir.resolve("cli.log");
    String project = "cli-watch-default-instructions-" + UUID.randomUUID();
    Process process = null;
    try {
      Files.writeString(sourceDir.resolve("Good.java"), "public class Good { void ok() {} }");
      process =
          startCliProcess(
              workDir,
              output,
              "--source",
              sourceDir.toString(),
              "--bolt",
              mg.getBoltUrl(),
              "--project",
              project,
              "--watch",
              "-t",
              "3",
              "--apply-schema",
              "--with-memories");

      Process runningProcess = process;
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(
              () -> {
                Path defaultInstructions = workDir.resolve("AGENTS.md");
                assertTrue(runningProcess.isAlive(), () -> readOutput(output));
                assertTrue(Files.exists(defaultInstructions), () -> readOutput(output));
                assertTrue(
                    Files.readString(defaultInstructions).contains("## Memories"),
                    "default instructions should be installed before watch mode blocks");
                assertTrue(
                    readOutput(output).contains("Connected to Memgraph at " + mg.getBoltUrl()),
                    () -> readOutput(output));
                assertTrue(
                    classExists(mg, project, "Good"),
                    "watch mode should still perform the initial ingestion");
              });
    } finally {
      stopProcess(process);
      wipeProject(mg, project);
      deleteDir(workDir);
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

  private static Process startCliProcess(Path workingDirectory, Path output, String... args)
      throws IOException {
    var command = new java.util.ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(IngesterCli.class.getName());
    command.addAll(java.util.List.of(args));
    return new ProcessBuilder(command)
        .directory(workingDirectory.toFile())
        .redirectErrorStream(true)
        .redirectOutput(output.toFile())
        .start();
  }

  private static void stopProcess(Process process) throws InterruptedException {
    if (process != null && process.isAlive()) {
      process.destroy();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private static String readOutput(Path output) {
    try {
      return Files.exists(output) ? Files.readString(output) : "";
    } catch (IOException e) {
      return "Could not read CLI output: " + e.getMessage();
    }
  }

  private static boolean classExists(MemgraphInstance mg, String project, String name) {
    try (var driver = MemgraphDriver.open(mg.getBoltUrl());
        var session = driver.session()) {
      long classCount =
          session
              .run(
                  "MATCH (c:Class {project: $p, name: $name}) RETURN count(c) AS n",
                  Map.of("p", project, "name", name))
              .single()
              .get("n")
              .asLong();
      return classCount > 0;
    }
  }

  private static void wipeProject(MemgraphInstance mg, String project) {
    try (var driver = MemgraphDriver.open(mg.getBoltUrl());
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
