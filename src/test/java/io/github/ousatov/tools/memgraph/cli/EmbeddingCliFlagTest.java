package io.github.ousatov.tools.memgraph.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;

/**
 * Pins picocli parsing of the negatable embedding flags: the plain form must enable, the {@code
 * --no-} form must disable, and the default is enabled. Guards against picocli's
 * toggle-against-default semantics, which otherwise parse a default-true negatable flag like {@code
 * --code-embeddings} as {@code false}.
 *
 * @author Oleksii Usatov
 */
class EmbeddingCliFlagTest {

  @Command
  static final class Harness {
    @ArgGroup(validate = false)
    CodeEmbeddingCliOptions codeEmbed = new CodeEmbeddingCliOptions();

    @ArgGroup(validate = false)
    MemoryEmbeddingCliOptions memoryEmbed = new MemoryEmbeddingCliOptions();
  }

  @Test
  void embeddingsAreEnabledByDefault() {
    Harness harness = parse();

    assertTrue(harness.codeEmbed.enabled);
    assertTrue(harness.memoryEmbed.enabled);
  }

  @Test
  void plainFlagEnablesEmbeddings() {
    Harness harness = parse("--code-embeddings", "--memory-embeddings");

    assertTrue(harness.codeEmbed.enabled);
    assertTrue(harness.memoryEmbed.enabled);
  }

  @Test
  void negatedFlagDisablesEmbeddings() {
    Harness harness = parse("--no-code-embeddings", "--no-memory-embeddings");

    assertFalse(harness.codeEmbed.enabled);
    assertFalse(harness.memoryEmbed.enabled);
  }

  private static Harness parse(String... args) {
    Harness harness = new Harness();
    new CommandLine(harness).parseArgs(args);
    return harness;
  }
}
