package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
