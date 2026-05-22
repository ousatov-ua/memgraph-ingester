package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for managed agent instruction installation.
 *
 * @author Oleksii Usatov
 */
class AgentInstructionsInstallerTest {

  @TempDir private Path tempDir;

  @Test
  void writesCodeInstructionsByDefault() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");

    AgentInstructionsInstaller.InstallResult result =
        AgentInstructionsInstaller.install(target, "sample-project", false);

    String content = Files.readString(target);
    assertEquals(target, result.target());
    assertFalse(result.includeMemories());
    assertTrue(content.contains("<!-- memgraph-ingester:start -->"));
    assertTrue(content.contains("Repo is indexed in Memgraph as **`sample-project`**"));
    assertTrue(content.contains("## Codebase Analysis Queries"));
    assertFalse(content.contains("## Memory Schema"));
  }

  @Test
  void includesMemoryInstructionsWhenRequested() throws IOException {
    Path target = tempDir.resolve("CLAUDE.md");

    AgentInstructionsInstaller.install(target, "memory-project", true);

    String content = Files.readString(target);
    assertTrue(content.contains("Repo is indexed in Memgraph as **`memory-project`**"));
    assertTrue(content.contains("## Memories"));
    assertTrue(content.contains("MATCH (m:Memory {project: 'memory-project'})"));
  }

  @Test
  void replacesExistingManagedBlock() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");
    AgentInstructionsInstaller.install(target, "old-project", true);
    String managedBlock = Files.readString(target);
    Files.writeString(target, "before\n\n" + managedBlock + "\nafter\n");

    AgentInstructionsInstaller.install(target, "new-project", false);

    String content = Files.readString(target);
    assertTrue(content.startsWith("before\n\n"));
    assertTrue(content.endsWith("\nafter\n"));
    assertTrue(content.contains("Repo is indexed in Memgraph as **`new-project`**"));
    assertFalse(content.contains("old-project"));
    assertFalse(content.contains("## Memory Schema"));
    assertEquals(1, countOccurrences(content, "<!-- memgraph-ingester:start -->"));
  }

  @Test
  void replacingManagedBlockIsIdempotent() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");
    AgentInstructionsInstaller.install(target, "same-project", false);
    String firstInstall = Files.readString(target);

    AgentInstructionsInstaller.install(target, "same-project", false);

    assertEquals(firstInstall, Files.readString(target));
  }

  @Test
  void replacesLegacyUnmarkedTemplateBlock() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");
    Files.writeString(
        target,
        """
        intro

        ## Knowledge Graph

        Repo is indexed in Memgraph as **`legacy-project`**. Every query MUST include
        `project: 'legacy-project'`.

        legacy body

        > Memory is not logs. Store only what improves future decisions.

        outro
        """);

    AgentInstructionsInstaller.install(target, "modern-project", false);

    String content = Files.readString(target);
    assertTrue(content.contains("intro"));
    assertTrue(content.contains("outro"));
    assertTrue(content.contains("<!-- memgraph-ingester:start -->"));
    assertTrue(content.contains("Repo is indexed in Memgraph as **`modern-project`**"));
    assertFalse(content.contains("legacy-project"));
  }

  @Test
  void resolvesDefaultInstructionFilesByAgent() {
    assertEquals(Path.of("AGENTS.md"), AgentInstructionsInstaller.defaultInstructionFile("codex"));
    assertEquals(Path.of("CLAUDE.md"), AgentInstructionsInstaller.defaultInstructionFile("claude"));
    assertEquals(Path.of("AGENTS.md"), AgentInstructionsInstaller.defaultInstructionFile("github"));
  }

  private static int countOccurrences(String content, String needle) {
    int count = 0;
    int index = content.indexOf(needle);
    while (index >= 0) {
      count++;
      index = content.indexOf(needle, index + needle.length());
    }
    return count;
  }
}
