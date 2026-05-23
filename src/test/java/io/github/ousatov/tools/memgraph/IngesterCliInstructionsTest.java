package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * CLI tests for agent instruction installation mode.
 *
 * @author Oleksii Usatov
 */
class IngesterCliInstructionsTest {

  @TempDir private Path tempDir;

  @Test
  void initInstructionsWritesCodeGuidanceWithoutSourceOrBolt() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");

    int exitCode =
        new CommandLine(new IngesterCli())
            .execute(
                "--init-instructions",
                "-P",
                "cli-code-project",
                "--instructions-file",
                target.toString());

    String content = Files.readString(target);
    assertEquals(0, exitCode);
    assertTrue(content.contains("Repo is indexed in Memgraph as **`cli-code-project`**"));
    assertFalse(content.contains("## Memory Schema"));
  }

  @Test
  void instructionsFileImpliesInitInstructions() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");

    int exitCode =
        new CommandLine(new IngesterCli())
            .execute("-P", "cli-file-project", "--instructions-file", target.toString());

    String content = Files.readString(target);
    assertEquals(0, exitCode);
    assertTrue(content.contains("Repo is indexed in Memgraph as **`cli-file-project`**"));
    assertFalse(content.contains("## Memory Schema"));
  }

  @Test
  void withMemoriesAppliesDefaultInstructions() throws Exception {
    CliProcessResult result =
        runCliIn(tempDir, "-P", "cli-default-memory-project", "--with-memories");

    String content = Files.readString(tempDir.resolve("AGENTS.md"));
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(content.contains("Repo is indexed in Memgraph as **`cli-default-memory-project`**"));
    assertTrue(content.contains("## Memories"));
  }

  @Test
  void instructionsFileContinuesToIngestionValidationWhenSourceIsProvided() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");
    Path source = Files.createDirectories(tempDir.resolve("src"));

    int exitCode =
        new CommandLine(new IngesterCli())
            .execute(
                "-P",
                "cli-continue-project",
                "--instructions-file",
                target.toString(),
                "-s",
                source.toString());

    String content = Files.readString(target);
    assertEquals(1, exitCode);
    assertTrue(content.contains("Repo is indexed in Memgraph as **`cli-continue-project`**"));
  }

  @Test
  void withMemoriesContinuesToIngestionValidationWhenSourceIsProvided() throws Exception {
    Path source = Files.createDirectories(tempDir.resolve("src"));

    CliProcessResult result =
        runCliIn(
            tempDir,
            "-P",
            "cli-js-memory-project",
            "--with-memories",
            "--source",
            source.toString());

    String content = Files.readString(tempDir.resolve("AGENTS.md"));
    assertEquals(1, result.exitCode(), result.output());
    assertTrue(content.contains("Repo is indexed in Memgraph as **`cli-js-memory-project`**"));
    assertTrue(content.contains("## Memories"));
  }

  @Test
  void withMemoriesContinuesToIngestionValidationWhenAuthIsProvided() throws Exception {
    CliProcessResult result =
        runCliIn(
            tempDir,
            "-P",
            "cli-auth-memory-project",
            "--with-memories",
            "-u",
            "neo4j",
            "-p",
            "secret");

    String content = Files.readString(tempDir.resolve("AGENTS.md"));
    assertEquals(1, result.exitCode(), result.output());
    assertTrue(content.contains("Repo is indexed in Memgraph as **`cli-auth-memory-project`**"));
    assertTrue(content.contains("## Memories"));
  }

  @Test
  void instructionsAgentImpliesInitInstructions() {
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    try {
      System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));

      int exitCode =
          new CommandLine(new IngesterCli())
              .execute("-P", "cli-agent-project", "--instructions-agent", "unsupported-agent");

      assertEquals(1, exitCode);
      assertTrue(
          stderr.toString(StandardCharsets.UTF_8).contains("Unsupported instructions agent"),
          "explicit --instructions-agent should enter instruction installation before ingest"
              + " validation");
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  void initInstructionsCanIncludeMemories() throws IOException {
    Path target = tempDir.resolve("CLAUDE.md");

    int exitCode =
        new CommandLine(new IngesterCli())
            .execute(
                "--init-instructions",
                "-P",
                "cli-memory-project",
                "--instructions-agent",
                "claude",
                "--instructions-file",
                target.toString(),
                "--with-memories");

    String content = Files.readString(target);
    assertEquals(0, exitCode);
    assertTrue(content.contains("## Memories"));
    assertTrue(content.contains("MATCH (m:Memory {project: 'cli-memory-project'})"));
  }

  private static CliProcessResult runCliIn(Path workingDirectory, String... args)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(IngesterCli.class.getName());
    command.addAll(List.of(args));
    Process process =
        new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(10, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(finished, () -> "CLI process did not exit. Output:\n" + output);
    return new CliProcessResult(process.exitValue(), output);
  }

  private static final class CliProcessResult {
    private final int exitCode;
    private final String output;

    private CliProcessResult(int exitCode, String output) {
      this.exitCode = exitCode;
      this.output = output;
    }

    private int exitCode() {
      return exitCode;
    }

    private String output() {
      return output;
    }
  }
}
