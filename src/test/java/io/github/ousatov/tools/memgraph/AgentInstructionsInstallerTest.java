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

    io.github.ousatov.tools.memgraph.vo.cli.InstallResult result =
        AgentInstructionsInstaller.install(target, "sample-project", false);

    String content = Files.readString(target);
    assertEquals(target, result.target());
    assertFalse(result.includeMemories());
    assertTrue(content.contains("<!-- memgraph-ingester:start -->"));
    assertTrue(content.contains("Repo is indexed in Memgraph as **`sample-project`**"));
    assertTrue(content.contains("memgraph-ingester-mcp"));
    assertTrue(content.contains("### MCP Tools"));
    assertTrue(content.contains("`code_search`: CodeChunk RAG discovery."));
    assertTrue(content.contains("Prefer dedicated tools over `raw_read_cypher`."));
    assertFalse(content.contains("Memory Schema"));
  }

  @Test
  void includesMemoryInstructionsWhenRequested() throws IOException {
    Path target = tempDir.resolve("CLAUDE.md");

    AgentInstructionsInstaller.install(target, "memory-project", true);

    String content = Files.readString(target);
    assertTrue(content.contains("Repo is indexed in Memgraph as **`memory-project`**"));
    assertTrue(content.contains("## Memories"));
    assertTrue(content.contains("memgraph-ingester-mcp"));
    assertTrue(content.contains("### MCP Tools"));
    assertTrue(
        content.contains(
            "`memory_search`: MemoryChunk RAG discovery with index-only hit metadata."));
    assertTrue(content.contains("`memory_upsert`: create/update"));
    assertTrue(content.contains("Controlled values are enforced by the MCP."));
    assertFalse(content.contains("Memory Schema"));
  }

  @Test
  void writesNoMcpCodeInstructionsWhenRequested() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");

    io.github.ousatov.tools.memgraph.vo.cli.InstallResult result =
        AgentInstructionsInstaller.install(target, "no-mcp-project", false, true);

    String content = Files.readString(target);
    assertEquals(target, result.target());
    assertFalse(result.includeMemories());
    assertTrue(content.contains("Repo is indexed in Memgraph as **`no-mcp-project`**"));
    assertTrue(content.contains("`project: 'no-mcp-project'`."));
    assertTrue(content.contains("Start `mgconsole`:"));
    assertFalse(content.contains("memgraph-ingester-mcp"));
    assertFalse(content.contains("### MCP Tools"));
    assertFalse(content.contains("Memory Schema"));
  }

  @Test
  void writesNoMcpMemoryInstructionsWhenRequested() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");

    AgentInstructionsInstaller.install(target, "no-mcp-memory-project", true, true);

    String content = Files.readString(target);
    assertTrue(content.contains("Repo is indexed in Memgraph as **`no-mcp-memory-project`**"));
    assertTrue(content.contains("`project: 'no-mcp-memory-project'`."));
    assertTrue(content.contains("### Memory Schema"));
    assertTrue(content.contains("Session Memory embedding refresh"));
    assertFalse(content.contains("memgraph-ingester-mcp"));
    assertFalse(content.contains("### MCP Tools"));
  }

  @Test
  void memoryInstructionsUseMcpToolsByDefault() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");

    AgentInstructionsInstaller.install(target, "memory-project", true);

    String content = Files.readString(target);
    assertTrue(
        content.contains(
            "`memory_search`: MemoryChunk RAG discovery with index-only hit metadata."));
    assertTrue(content.contains("`memory_get`: canonical Memory node plus resolved CodeRefs."));
    assertTrue(content.contains("`memory_refresh_embeddings`: refresh selected MemoryChunk"));
    assertFalse(content.contains("memory.status AS status, chunk.text AS text"));
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
  void replacesManagedBlockWhenEarlierUserTextMentionsEndMarker() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");
    AgentInstructionsInstaller.install(target, "old-project", true);
    String managedBlock = Files.readString(target);
    Files.writeString(
        target,
        """
        notes mention <!-- memgraph-ingester:end --> literally

        """
            + managedBlock);

    AgentInstructionsInstaller.install(target, "new-project", false);

    String content = Files.readString(target);
    assertTrue(content.contains("notes mention <!-- memgraph-ingester:end --> literally"));
    assertTrue(content.contains("Repo is indexed in Memgraph as **`new-project`**"));
    assertFalse(content.contains("old-project"));
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
  void replacesAllLegacyUnmarkedTemplateBlocks() throws IOException {
    Path target = tempDir.resolve("AGENTS.md");
    Files.writeString(
        target,
        "intro\n\n"
            + legacyBlock("legacy-one")
            + "\nmiddle\n\n"
            + legacyBlock("legacy-two")
            + "\noutro\n");

    AgentInstructionsInstaller.install(target, "modern-project", false);

    String content = Files.readString(target);
    assertTrue(content.contains("intro"));
    assertTrue(content.contains("middle"));
    assertTrue(content.contains("outro"));
    assertTrue(content.contains("Repo is indexed in Memgraph as **`modern-project`**"));
    assertFalse(content.contains("legacy-one"));
    assertFalse(content.contains("legacy-two"));
    assertEquals(1, countOccurrences(content, "<!-- memgraph-ingester:start -->"));
    assertEquals(1, countOccurrences(content, "Repo is indexed in Memgraph as"));
  }

  @Test
  void resolvesDefaultInstructionFilesByAgent() {
    assertEquals(Path.of("AGENTS.md"), AgentInstructionsInstaller.defaultInstructionFile("codex"));
    assertEquals(Path.of("CLAUDE.md"), AgentInstructionsInstaller.defaultInstructionFile("claude"));
    assertEquals(Path.of("AGENTS.md"), AgentInstructionsInstaller.defaultInstructionFile("github"));
  }

  private static String legacyBlock(String project) {
    return """
    ## Knowledge Graph

    Repo is indexed in Memgraph as **`%s`**. Every query MUST include
    `project: '%s'`.

    legacy body

    > Memory is not logs. Store only what improves future decisions.
    """
        .formatted(project, project);
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
