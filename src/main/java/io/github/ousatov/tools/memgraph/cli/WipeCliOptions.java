package io.github.ousatov.tools.memgraph.cli;

import io.github.ousatov.tools.memgraph.def.Const;
import picocli.CommandLine.Option;

/**
 * Picocli {@code @ArgGroup} binding for project-wipe options.
 *
 * @author Oleksii Usatov
 */
public final class WipeCliOptions {

  @Option(
      names = "--wipe-project-code",
      description = "Delete the code graph belonging to this project before ingesting")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean projectCode;

  @Option(
      names = "--wipe-project-memories",
      description = "Delete the memory graph belonging to this project before ingesting")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean projectMemories;

  @Option(
      names = "--wipe-code-rag",
      description = "Delete derived CodeChunk rows for this project before ingesting")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean codeRag;

  @Option(
      names = "--wipe-memory-rag",
      description = "Delete derived MemoryChunk rows for this project before ingesting")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean memoryRag;
}
