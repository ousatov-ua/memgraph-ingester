package io.github.ousatov.tools.memgraph.cli;

import io.github.ousatov.tools.memgraph.def.Const;
import java.nio.file.Path;
import picocli.CommandLine.Option;

/**
 * Picocli {@code @ArgGroup} binding for agent instruction installation options.
 *
 * @author Oleksii Usatov
 */
public final class InstructionsCliOptions {

  @Option(
      names = {"--init-instructions"},
      description =
          "Write or replace managed Memgraph agent instructions. Code guidance is included by "
              + "default; add --with-memories for Memory workflow guidance.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean initInstructions;

  @Option(
      names = {Const.Cli.INSTRUCTIONS_AGENT},
      defaultValue = Const.SystemParams.CODEX,
      description =
          "Agent preset for instruction installation: codex, claude, gemini, github, or copilot."
              + " Implies --init-instructions when explicitly provided. Defaults to codex.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String instructionsAgent = Const.SystemParams.CODEX;

  @Option(
      names = {"--instructions-file"},
      description =
          "Instruction file to update for instruction installation. Overrides"
              + " --instructions-agent and implies --init-instructions.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public Path instructionsFile;

  @Option(
      names = {"--with-memories"},
      description =
          "Apply managed agent instructions with optional Memory workflow guidance, and enable"
              + " MemoryChunk refresh for ingestion runs. Uses the default instructions agent"
              + " unless --instructions-agent or --instructions-file is provided.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean withMemories;
}
