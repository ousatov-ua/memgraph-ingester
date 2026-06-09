package io.github.ousatov.tools.memgraph;

import io.github.ousatov.tools.memgraph.cli.CodeEmbeddingCliOptions;
import io.github.ousatov.tools.memgraph.cli.CtagsRuntimeCliOptions;
import io.github.ousatov.tools.memgraph.cli.InstructionsCliOptions;
import io.github.ousatov.tools.memgraph.cli.JsRuntimeCliOptions;
import io.github.ousatov.tools.memgraph.cli.MemoryEmbeddingCliOptions;
import io.github.ousatov.tools.memgraph.cli.PythonRuntimeCliOptions;
import io.github.ousatov.tools.memgraph.cli.WipeCliOptions;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Cli;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapterFactory;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedPythonRuntime;
import io.github.ousatov.tools.memgraph.exe.ingestion.IngestionOrchestrator;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleOutput;
import io.github.ousatov.tools.memgraph.exe.output.SimpleLoggerFile;
import io.github.ousatov.tools.memgraph.exe.smoke.CtagsRuntimeSmokeCheck;
import io.github.ousatov.tools.memgraph.exe.smoke.JsRuntimeSmokeCheck;
import io.github.ousatov.tools.memgraph.exe.smoke.PythonRuntimeSmokeCheck;
import io.github.ousatov.tools.memgraph.schema.MemgraphDriver;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import io.github.ousatov.tools.memgraph.vo.Settings;
import io.github.ousatov.tools.memgraph.vo.adapter.CtagsRuntimeOptions;
import io.github.ousatov.tools.memgraph.vo.adapter.JsRuntimeOptions;
import io.github.ousatov.tools.memgraph.vo.adapter.PythonRuntimeOptions;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * CLI entry point. Parses arguments and delegates to {@link IngestionOrchestrator}.
 *
 * <p>Exit codes: {@code 0} = success, {@code 1} = invalid arguments, {@code 2} = one or more files
 * failed to parse or ingest.
 *
 * @author Oleksii Usatov
 */
@Command(name = "ingest", mixinStandardHelpOptions = true, version = Cli.VERSION)
public final class IngesterCli implements Callable<Integer> {
  private static final SimpleLoggerFile SIMPLE_LOGGER_FILE = SimpleLoggerFile.configure();
  private static final Logger log = LoggerFactory.getLogger(IngesterCli.class);

  static {
    SIMPLE_LOGGER_FILE.restoreConsole();
  }

  @Spec private CommandSpec commandSpec;

  // ---- Core connection options ----

  @Option(
      names = {"-s", "--source"},
      description = "Root source directory (e.g. src/main/java)")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private Path sourceRoot;

  @Option(
      names = {"-b", "--bolt"},
      description = "Bolt URL, e.g. bolt://host:7687")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String boltUrl;

  @Option(
      names = {Const.Cli.USER_SHORT, Const.Cli.USER},
      defaultValue = Const.Symbols.EMPTY)
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String user;

  @Option(
      names = {Const.Cli.PASS_SHORT, Const.Cli.PASS},
      defaultValue = Const.Symbols.EMPTY)
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String pass;

  @Option(
      names = {"-P", "--project"},
      description =
          "Logical project name; namespaces all nodes so multiple "
              + "projects can share one Memgraph instance")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String project;

  @Option(
      names = {Const.Cli.THREADS_SHORT, Const.Cli.THREADS},
      defaultValue = Const.Params.ONE,
      description =
          "Number of parser threads. Each thread gets its own Bolt session. "
              + "Defaults to 1 (sequential). Values above the number of CPU cores rarely help "
              + "because Memgraph serializes writes internally.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int threads;

  @Option(
      names = {Const.Cli.CLASSPATH},
      defaultValue = Const.Symbols.EMPTY,
      description =
          "Additional classpath entries (JARs) for symbol resolution, separated by "
              + "the platform path separator. Improves CALLS edge and type resolution coverage.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String classpath;

  @Option(
      names = {"--apply-schema"},
      description = "Apply schema on Memgraph")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean applySchema;

  @Option(
      names = {"--wipe-all"},
      description = "Wipe all data from Memgraph")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean wipeAllData;

  @Option(
      names = {"-w", "--watch"},
      description = "Watch for changes in the source directory and automatically re-ingest")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean watch;

  // ---- Grouped options ----

  @ArgGroup(validate = false, heading = "Wipe options:%n")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private WipeCliOptions wipe = new WipeCliOptions();

  @ArgGroup(validate = false, heading = "Code embedding options:%n")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private CodeEmbeddingCliOptions codeEmbed = new CodeEmbeddingCliOptions();

  @ArgGroup(validate = false, heading = "Memory embedding options:%n")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private MemoryEmbeddingCliOptions memoryEmbed = new MemoryEmbeddingCliOptions();

  @ArgGroup(validate = false, heading = "JavaScript runtime options:%n")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private JsRuntimeCliOptions jsRuntime = new JsRuntimeCliOptions();

  @ArgGroup(validate = false, heading = "Python runtime options:%n")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private PythonRuntimeCliOptions pythonRuntime = new PythonRuntimeCliOptions();

  @ArgGroup(validate = false, heading = "Universal Ctags runtime options:%n")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private CtagsRuntimeCliOptions ctagsRuntime = new CtagsRuntimeCliOptions();

  @ArgGroup(validate = false, heading = "Agent instructions options:%n")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private InstructionsCliOptions instructions = new InstructionsCliOptions();

  // ---- Entry point ----

  /** Entry point. */
  public static void main(String[] args) {
    int exit = new CommandLine(new IngesterCli()).execute(args);
    System.exit(exit);
  }

  @Override
  public Integer call() {
    ConsoleOutput.printTitle();
    if (shouldInstallInstructions()) {
      Integer instructionsExitCode = installAgentInstructions();
      if (instructionsExitCode != 0 || !isFollowOnCliActionRequested()) {
        return instructionsExitCode;
      }
    }

    SelectedRuntimeModes runtimeModes = selectedRuntimeModes();
    if (runtimeModes == null) {
      return 1;
    }
    Integer checkExit = runRuntimeChecks(runtimeModes);
    if (checkExit != null) {
      return checkExit;
    }
    if (!validateIngestionInputs()) {
      return 1;
    }
    SelectedEmbeddingSettings embeddingSettings = selectedEmbeddingSettings();
    if (embeddingSettings == null) {
      return 1;
    }
    return ingest(runtimeModes, embeddingSettings);
  }

  private SelectedRuntimeModes selectedRuntimeModes() {
    try {
      return new SelectedRuntimeModes(
          jsRuntime.parsedMode(), pythonRuntime.parsedMode(), ctagsRuntime.parsedMode());
    } catch (IllegalArgumentException e) {
      log.error(e.getMessage());
      return null;
    }
  }

  private Integer runRuntimeChecks(SelectedRuntimeModes runtimeModes) {
    if (!(jsRuntime.check || pythonRuntime.check || ctagsRuntime.check)) {
      return null;
    }
    int checkExit = 0;
    if (jsRuntime.check) {
      checkExit =
          Math.max(
              checkExit,
              new JsRuntimeSmokeCheck(
                      jsRuntime.resolvedCache(),
                      jsRuntime.nodeVersion,
                      jsRuntime.typescriptVersion,
                      runtimeModes.js())
                  .run());
    }
    if (pythonRuntime.check) {
      checkExit =
          Math.max(
              checkExit,
              new PythonRuntimeSmokeCheck(
                      pythonRuntime.resolvedCache(),
                      pythonRuntime.version,
                      pythonRuntime.build,
                      runtimeModes.python())
                  .run());
    }
    if (ctagsRuntime.check) {
      checkExit =
          Math.max(
              checkExit,
              new CtagsRuntimeSmokeCheck(
                      ctagsRuntime.resolvedCache(), ctagsRuntime.version, runtimeModes.ctags())
                  .run());
    }
    return checkExit;
  }

  private boolean validateIngestionInputs() {
    if (threads < 1) {
      log.error("--threads must be >= 1 (got {})", threads);
      return false;
    }
    if (sourceRoot == null) {
      log.error("--source is required");
      return false;
    }
    if (!Files.isDirectory(sourceRoot)) {
      log.error("--source must be an existing directory: {}", sourceRoot);
      return false;
    }
    if (boltUrl == null || boltUrl.isBlank()) {
      log.error("--bolt is required");
      return false;
    }
    if (project == null || project.isBlank()) {
      log.error("--project is required");
      return false;
    }
    return true;
  }

  private SelectedEmbeddingSettings selectedEmbeddingSettings() {
    try {
      boolean memoryEmbeddingsRequested =
          instructions.withMemories
              || wipe.memoryRag
              || hasMatchedOption(Const.Cli.MEMORY_EMBEDDINGS);
      return new SelectedEmbeddingSettings(
          codeEmbed.toSettings(hasMatchedOption(Const.Cli.CODE_EMBEDDINGS)),
          memoryEmbed.toSettings(memoryEmbeddingsRequested, memoryEmbeddingsRequested));
    } catch (IllegalArgumentException e) {
      log.error(e.getMessage());
      return null;
    }
  }

  private Integer ingest(
      SelectedRuntimeModes runtimeModes, SelectedEmbeddingSettings embeddingSettings) {
    log.debug("Using next classpath entries: {}", classpath);
    try (Driver driver = MemgraphDriver.open(boltUrl, user, pass)) {
      driver.verifyConnectivity();
      log.info("Connected to Memgraph at {}", boltUrl);
      List<LanguageAdapter<?>> languageAdapters =
          LanguageAdapterFactory.create(
              sourceRoot,
              classpath,
              new JsRuntimeOptions(
                  jsRuntime.resolvedCache(),
                  jsRuntime.nodeVersion,
                  jsRuntime.typescriptVersion,
                  runtimeModes.js()),
              new PythonRuntimeOptions(
                  pythonRuntime.resolvedCache(),
                  pythonRuntime.version,
                  pythonRuntime.build,
                  runtimeModes.python()),
              new CtagsRuntimeOptions(
                  ctagsRuntime.resolvedCache(), ctagsRuntime.version, runtimeModes.ctags()));
      log.info(
          "Enabled source language adapters: {}",
          languageAdapters.stream().map(LanguageAdapter::displayName).toList());
      IngestionOrchestrator orchestrator =
          new IngestionOrchestrator(sourceRoot, project, threads, driver, languageAdapters);
      var settings =
          new Settings(
              wipeAllData,
              applySchema,
              wipe.projectCode,
              wipe.projectMemories,
              wipe.codeRag,
              wipe.memoryRag,
              watch,
              analysisCacheKey(runtimeModes.js(), runtimeModes.python(), runtimeModes.ctags()),
              embeddingSettings.code(),
              embeddingSettings.memory());
      int failures = orchestrator.run(settings);
      if (failures > 0) {
        log.error("Ingestion finished with {} file failure(s).", failures);
        return 2;
      }
    }
    log.info("Ingestion complete for project '{}'.", project);
    return 0;
  }

  private record SelectedRuntimeModes(RuntimeMode js, RuntimeMode python, RuntimeMode ctags) {}

  private record SelectedEmbeddingSettings(EmbeddingSettings code, EmbeddingSettings memory) {}

  private boolean shouldInstallInstructions() {
    return instructions.initInstructions
        || instructions.withMemories
        || instructions.instructionsFile != null
        || hasMatchedOption(Const.Cli.INSTRUCTIONS_AGENT);
  }

  private boolean isFollowOnCliActionRequested() {
    return jsRuntime.check
        || pythonRuntime.check
        || ctagsRuntime.check
        || sourceRoot != null
        || boltUrl != null
        || watch
        || applySchema
        || wipeAllData
        || wipe.projectCode
        || wipe.projectMemories
        || wipe.codeRag
        || wipe.memoryRag
        || hasMatchedOption(Const.Cli.THREADS)
        || hasMatchedOption(Const.Cli.THREADS_SHORT)
        || hasMatchedOption(Const.Cli.USER)
        || hasMatchedOption(Const.Cli.USER_SHORT)
        || hasMatchedOption(Const.Cli.PASS)
        || hasMatchedOption(Const.Cli.PASS_SHORT)
        || hasMatchedOption(Const.Cli.CLASSPATH)
        || hasMatchedOption(Const.Cli.JS_RUNTIME_MODE)
        || hasMatchedOption(Const.Cli.JS_RUNTIME_CACHE)
        || hasMatchedOption(Const.Cli.JS_NODE_VERSION)
        || hasMatchedOption(Const.Cli.JS_TYPESCRIPT_VERSION)
        || hasMatchedOption(Const.Cli.PYTHON_RUNTIME_MODE)
        || hasMatchedOption(Const.Cli.PYTHON_RUNTIME_CACHE)
        || hasMatchedOption(Const.Cli.PYTHON_VERSION)
        || hasMatchedOption(Const.Cli.PYTHON_BUILD);
  }

  private boolean hasMatchedOption(String optionName) {
    return commandSpec != null
        && commandSpec.commandLine() != null
        && commandSpec.commandLine().getParseResult() != null
        && commandSpec.commandLine().getParseResult().hasMatchedOption(optionName);
  }

  private String analysisCacheKey(
      RuntimeMode selectedJsRuntimeMode,
      RuntimeMode selectedPythonRuntimeMode,
      RuntimeMode selectedCtagsRuntimeMode) {
    // v2: embedding-oriented CodeChunk text format — forces a full re-ingest so every chunk is
    // rebuilt and re-embedded with the new layout.
    return new StringJoiner("|")
        .add("v2")
        .add("classpath=" + value(classpath))
        .add("jsCache=" + value(jsRuntime.resolvedCache()))
        .add("jsNode=" + value(jsRuntime.nodeVersion))
        .add("jsTypescript=" + value(jsRuntime.typescriptVersion))
        .add("jsMode=" + value(selectedJsRuntimeMode))
        .add("pythonCache=" + value(pythonRuntime.resolvedCache()))
        .add("pythonVersion=" + value(pythonRuntime.version))
        .add("pythonBuild=" + value(pythonRuntime.build))
        .add("pythonMode=" + value(selectedPythonRuntimeMode))
        .add("ctagsCache=" + value(ctagsRuntime.resolvedCache()))
        .add("ctagsVersion=" + value(ctagsRuntime.version))
        .add("ctagsMode=" + value(selectedCtagsRuntimeMode))
        .toString();
  }

  private static String value(Object value) {
    return value == null ? Const.Symbols.EMPTY : value.toString();
  }

  private Integer installAgentInstructions() {
    try {
      Path target =
          instructions.instructionsFile == null
              ? AgentInstructionsInstaller.defaultInstructionFile(instructions.instructionsAgent)
              : instructions.instructionsFile;
      io.github.ousatov.tools.memgraph.vo.cli.InstallResult result =
          AgentInstructionsInstaller.install(target, project, instructions.withMemories);
      ConsoleOutput.success(
          String.format(
              "Updated Memgraph instructions in %s with project '%s' (memories: %s).",
              result.target(), project, result.includeMemories()));
      return 0;
    } catch (IllegalArgumentException | IllegalStateException | IOException e) {
      log.error(e.getMessage());
      ConsoleOutput.line("Error: " + e.getMessage());
      return 1;
    }
  }

  /**
   * @see IngesterCliTest#pythonRuntimeCacheDefaultsIndependentlyOfJsRuntimeCache
   */
  static Path resolvePythonRuntimeCache(Path pythonRuntimeCache) {
    return pythonRuntimeCache == null
        ? ManagedPythonRuntime.defaultCacheRoot()
        : pythonRuntimeCache;
  }
}
