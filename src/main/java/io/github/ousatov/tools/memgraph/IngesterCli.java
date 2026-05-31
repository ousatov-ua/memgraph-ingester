package io.github.ousatov.tools.memgraph;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapterFactory;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedCtagsRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedNodeRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedPythonRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedTypescriptPackage;
import io.github.ousatov.tools.memgraph.exe.ingestion.IngestionOrchestrator;
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
import java.util.concurrent.Callable;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
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
@Command(name = "ingest", mixinStandardHelpOptions = true, version = "12.0.28")
public final class IngesterCli implements Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(IngesterCli.class);

  @Spec private CommandSpec commandSpec;

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
      names = "--wipe-project-code",
      description = "Delete the code graph belonging to this project before ingesting")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean wipeProjectCode;

  @Option(
      names = "--wipe-project-memories",
      description = "Delete the memory graph belonging to this project before ingesting")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean wipeProjectMemories;

  @Option(
      names = "--wipe-code-rag",
      description = "Delete derived CodeChunk rows for this project before ingesting")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean wipeCodeRag;

  @Option(
      names = "--wipe-memory-rag",
      description = "Delete derived MemoryChunk rows for this project before ingesting")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean wipeMemoryRag;

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
      names = {"--incremental"},
      description = "Skip files whose last-modified timestamp matches the stored value")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean incremental;

  @Option(
      names = {"-w", "--watch"},
      description = "Watch for changes in the source directory and automatically re-ingest")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean watch;

  @Option(
      names = {"--code-embeddings"},
      defaultValue = Const.Params.TRUE,
      negatable = true,
      description =
          "Use Memgraph's embeddings module for stale :CodeChunk embeddings after"
              + " ingestion and watch updates.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean codeEmbeddings;

  @Option(
      names = {"--code-embedding-device"},
      defaultValue = Const.Symbols.EMPTY,
      description =
          "Memgraph embeddings device. Leave blank for Memgraph auto-selection, or use cpu, cuda, "
              + "all, cuda:0, etc.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String codeEmbeddingDevice;

  @Option(
      names = {"--code-embedding-batch-size"},
      defaultValue = Const.Symbols.EMPTY + EmbeddingSettings.DEFAULT_BATCH_SIZE,
      description =
          "Chunk nodes per Memgraph embedding call and local embedding batch size for CodeChunk"
              + " refresh.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int codeEmbeddingBatchSize;

  @Option(
      names = {"--code-embedding-chunk-size"},
      defaultValue = Const.Symbols.EMPTY + EmbeddingSettings.DEFAULT_CHUNK_SIZE,
      description = "Memgraph embeddings chunk_size for local multi-GPU computation.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int codeEmbeddingChunkSize;

  @Option(
      names = {"--code-embedding-remote-batch-size"},
      defaultValue = Const.Params.ZERO,
      description = "Optional Memgraph remote_batch_size; 0 keeps the embeddings module default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int codeEmbeddingRemoteBatchSize;

  @Option(
      names = {"--code-embedding-concurrency"},
      defaultValue = Const.Params.ZERO,
      description = "Optional Memgraph remote provider concurrency; 0 keeps the module default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int codeEmbeddingConcurrency;

  @Option(
      names = {"--code-embedding-index-capacity"},
      defaultValue = Const.Params.ZERO,
      description =
          "Optional vector index capacity; 0 uses the current CodeChunk count. The index uses "
              + "cosine metric and f16 scalar storage by default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int codeEmbeddingIndexCapacity;

  @Option(
      names = {"--memory-embeddings"},
      defaultValue = Const.Params.TRUE,
      negatable = true,
      description =
          "With --with-memories, sync :MemoryChunk rows and refresh stale embeddings after"
              + " ingestion and watch updates.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean memoryEmbeddings;

  @Option(
      names = {"--memory-embedding-device"},
      defaultValue = Const.Symbols.EMPTY,
      description =
          "Memgraph embeddings device for MemoryChunk refresh. Leave blank for Memgraph"
              + " auto-selection, or use cpu, cuda, all, cuda:0, etc.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String memoryEmbeddingDevice;

  @Option(
      names = {"--memory-embedding-batch-size"},
      defaultValue = Const.Symbols.EMPTY + EmbeddingSettings.DEFAULT_BATCH_SIZE,
      description =
          "Chunk nodes per Memgraph embedding call and local embedding batch size for MemoryChunk"
              + " refresh.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int memoryEmbeddingBatchSize;

  @Option(
      names = {"--memory-embedding-chunk-size"},
      defaultValue = Const.Symbols.EMPTY + EmbeddingSettings.DEFAULT_CHUNK_SIZE,
      description = "Memgraph embeddings chunk_size for local MemoryChunk computation.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int memoryEmbeddingChunkSize;

  @Option(
      names = {"--memory-embedding-remote-batch-size"},
      defaultValue = Const.Params.ZERO,
      description =
          "Optional Memgraph remote_batch_size for MemoryChunk refresh; 0 keeps the embeddings"
              + " module default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int memoryEmbeddingRemoteBatchSize;

  @Option(
      names = {"--memory-embedding-concurrency"},
      defaultValue = Const.Params.ZERO,
      description =
          "Optional Memgraph remote provider concurrency for MemoryChunk refresh; 0 keeps the"
              + " module default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int memoryEmbeddingConcurrency;

  @Option(
      names = {"--memory-embedding-index-capacity"},
      defaultValue = Const.Params.ZERO,
      description =
          "Optional MemoryChunk vector index capacity; 0 uses the current MemoryChunk count. The"
              + " index uses cosine metric and f16 scalar storage by default.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private int memoryEmbeddingIndexCapacity;

  @Option(
      names = {Const.Cli.CLASSPATH},
      defaultValue = Const.Symbols.EMPTY,
      description =
          "Additional classpath entries (JARs) for symbol resolution, separated by "
              + "the platform path separator. Improves CALLS edge and type resolution coverage.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String classpath;

  @Option(
      names = {Const.Cli.JS_RUNTIME_MODE},
      defaultValue = Const.SystemParams.MANAGED,
      description =
          "JavaScript runtime mode: managed downloads Node.js, system uses node from PATH,"
              + " offline requires a warmed managed cache. Defaults to managed.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String jsRuntimeMode;

  @Option(
      names = {Const.Cli.JS_RUNTIME_CACHE},
      description = "Cache directory for managed Node.js and TypeScript downloads.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private Path jsRuntimeCache;

  @Option(
      names = {Const.Cli.JS_NODE_VERSION},
      defaultValue = ManagedNodeRuntime.DEFAULT_NODE_VERSION,
      description = "Pinned Node.js version used for managed JavaScript parsing.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String jsNodeVersion;

  @Option(
      names = {Const.Cli.JS_TYPESCRIPT_VERSION},
      defaultValue = ManagedTypescriptPackage.DEFAULT_TYPESCRIPT_VERSION,
      description = "Pinned TypeScript compiler package used by the JavaScript analyzer.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String jsTypescriptVersion;

  @Option(
      names = {"--check-js-runtime"},
      description =
          "Download/cache the managed JavaScript parser runtime if needed and run a local "
              + "parser smoke check without connecting to Memgraph.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean checkJsRuntime;

  @Option(
      names = {Const.Cli.PYTHON_RUNTIME_MODE},
      defaultValue = Const.SystemParams.MANAGED,
      description =
          "Python runtime mode: managed downloads standalone CPython and creates a private venv,"
              + " system uses Python 3.9+ from PATH, offline requires a warmed managed cache."
              + " Defaults to managed.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String pythonRuntimeMode;

  @Option(
      names = {Const.Cli.PYTHON_RUNTIME_CACHE},
      description = "Cache directory for managed CPython downloads and private Python venvs.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private Path pythonRuntimeCache;

  @Option(
      names = {Const.Cli.PYTHON_VERSION},
      defaultValue = ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
      description = "Pinned CPython version used for managed Python parsing.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String pythonVersion;

  @Option(
      names = {Const.Cli.PYTHON_BUILD},
      defaultValue = ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
      description = "Pinned python-build-standalone release tag used for managed Python parsing.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String pythonBuild;

  @Option(
      names = {"--check-python-runtime"},
      description =
          "Download/cache the managed Python parser runtime if needed and run a local parser "
              + "smoke check without connecting to Memgraph.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean checkPythonRuntime;

  @Option(
      names = {"--ctags-runtime-mode"},
      defaultValue = Const.SystemParams.MANAGED,
      description =
          "Universal Ctags runtime mode: managed downloads a verified ctags binary, system uses "
              + "ctags from PATH, offline requires a warmed managed cache. Defaults to managed.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String ctagsRuntimeMode;

  @Option(
      names = {"--ctags-runtime-cache"},
      description = "Cache directory for managed Universal Ctags downloads.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private Path ctagsRuntimeCache;

  @Option(
      names = {"--ctags-version"},
      defaultValue = ManagedCtagsRuntime.DEFAULT_CTAGS_VERSION,
      description = "Universal Ctags release tag used for managed fallback parsing, or latest.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String ctagsVersion;

  @Option(
      names = {"--check-ctags-runtime"},
      description =
          "Download/cache the managed Universal Ctags runtime if needed and run a local parser "
              + "smoke check without connecting to Memgraph.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean checkCtagsRuntime;

  @Option(
      names = {"--init-instructions"},
      description =
          "Write or replace managed Memgraph agent instructions. Code guidance is included by "
              + "default; add --with-memories for Memory workflow guidance.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean initInstructions;

  @Option(
      names = {Const.Cli.INSTRUCTIONS_AGENT},
      defaultValue = Const.SystemParams.CODEX,
      description =
          "Agent preset for instruction installation: codex, claude, gemini, github, or copilot. "
              + "Implies --init-instructions when explicitly provided. Defaults to codex.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private String instructionsAgent;

  @Option(
      names = {"--instructions-file"},
      description =
          "Instruction file to update for instruction installation. Overrides --instructions-agent "
              + "and implies --init-instructions.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private Path instructionsFile;

  @Option(
      names = {Const.Cli.NO_MEMGRAPH_INGESTER_MCP},
      description =
          "Use raw Memgraph/Cypher agent instructions instead of memgraph-ingester-mcp tool "
              + "guidance. Implies --init-instructions when explicitly provided.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean noMemgraphIngesterMcp;

  @Option(
      names = {"--with-memories"},
      description =
          "Apply managed agent instructions with optional Memory workflow guidance, and enable"
              + " MemoryChunk refresh for ingestion runs. Uses the default instructions agent"
              + " unless --instructions-agent or --instructions-file is provided.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  private boolean withMemories;

  /** Entry point. */
  public static void main(String[] args) {
    int exit = new CommandLine(new IngesterCli()).execute(args);
    System.exit(exit);
  }

  @Override
  public Integer call() {
    if (shouldInstallInstructions()) {
      Integer instructionsExitCode = installAgentInstructions();
      if (instructionsExitCode != 0 || !isFollowOnCliActionRequested()) {
        return instructionsExitCode;
      }
    }

    RuntimeMode selectedJsRuntimeMode;
    RuntimeMode selectedPythonRuntimeMode;
    RuntimeMode selectedCtagsRuntimeMode;
    try {
      selectedJsRuntimeMode = RuntimeMode.parse(jsRuntimeMode, "JS");
      selectedPythonRuntimeMode =
          RuntimeMode.parse(pythonRuntimeMode, Const.SystemParams.PYTHON_DISPLAY);
      selectedCtagsRuntimeMode = RuntimeMode.parse(ctagsRuntimeMode, Const.SystemParams.CTAGS);
    } catch (IllegalArgumentException e) {
      log.error(e.getMessage());
      return 1;
    }
    if (checkJsRuntime || checkPythonRuntime || checkCtagsRuntime) {
      int checkExit = 0;
      if (checkJsRuntime) {
        checkExit =
            Math.max(
                checkExit,
                new JsRuntimeSmokeCheck(
                        resolvedJsRuntimeCache(),
                        jsNodeVersion,
                        jsTypescriptVersion,
                        selectedJsRuntimeMode)
                    .run());
      }
      if (checkPythonRuntime) {
        checkExit =
            Math.max(
                checkExit,
                new PythonRuntimeSmokeCheck(
                        resolvedPythonRuntimeCache(),
                        pythonVersion,
                        pythonBuild,
                        selectedPythonRuntimeMode)
                    .run());
      }
      if (checkCtagsRuntime) {
        checkExit =
            Math.max(
                checkExit,
                new CtagsRuntimeSmokeCheck(
                        resolvedCtagsRuntimeCache(), ctagsVersion, selectedCtagsRuntimeMode)
                    .run());
      }
      return checkExit;
    }
    if (threads < 1) {
      log.error("--threads must be >= 1 (got {})", threads);
      return 1;
    }
    EmbeddingSettings codeEmbeddingSettings;
    EmbeddingSettings memoryEmbeddingSettings;
    try {
      codeEmbeddingSettings =
          new EmbeddingSettings(
              codeEmbeddings,
              EmbeddingSettings.DEFAULT_CODE_INDEX_NAME,
              EmbeddingSettings.DEFAULT_MODEL_NAME,
              EmbeddingSettings.DEFAULT_METRIC,
              EmbeddingSettings.DEFAULT_SCALAR_KIND,
              codeEmbeddingBatchSize,
              codeEmbeddingChunkSize,
              codeEmbeddingDevice,
              0,
              codeEmbeddingRemoteBatchSize,
              codeEmbeddingConcurrency,
              codeEmbeddingIndexCapacity);
      memoryEmbeddingSettings =
          new EmbeddingSettings(
              (withMemories || wipeMemoryRag) && memoryEmbeddings,
              EmbeddingSettings.DEFAULT_MEMORY_INDEX_NAME,
              EmbeddingSettings.DEFAULT_MODEL_NAME,
              EmbeddingSettings.DEFAULT_METRIC,
              EmbeddingSettings.DEFAULT_SCALAR_KIND,
              memoryEmbeddingBatchSize,
              memoryEmbeddingChunkSize,
              memoryEmbeddingDevice,
              0,
              memoryEmbeddingRemoteBatchSize,
              memoryEmbeddingConcurrency,
              memoryEmbeddingIndexCapacity);
    } catch (IllegalArgumentException e) {
      log.error(e.getMessage());
      return 1;
    }
    if (sourceRoot == null) {
      log.error("--source is required");
      return 1;
    }
    if (!Files.isDirectory(sourceRoot)) {
      log.error("--source must be an existing directory: {}", sourceRoot);
      return 1;
    }
    if (boltUrl == null || boltUrl.isBlank()) {
      log.error("--bolt is required");
      return 1;
    }
    if (project == null || project.isBlank()) {
      log.error("--project is required");
      return 1;
    }
    log.debug("Using next classpath entries: {}", classpath);
    try (Driver driver = MemgraphDriver.open(boltUrl, user, pass)) {
      driver.verifyConnectivity();
      log.info("Connected to Memgraph at {}", boltUrl);
      List<LanguageAdapter<?>> languageAdapters =
          createLanguageAdapters(
              selectedJsRuntimeMode, selectedPythonRuntimeMode, selectedCtagsRuntimeMode);
      log.info(
          "Enabled source language adapters: {}",
          languageAdapters.stream().map(LanguageAdapter::displayName).toList());
      IngestionOrchestrator orchestrator =
          new IngestionOrchestrator(sourceRoot, project, threads, driver, languageAdapters);
      var settings =
          new Settings(
              wipeAllData,
              applySchema,
              wipeProjectCode,
              wipeProjectMemories,
              wipeCodeRag,
              wipeMemoryRag,
              incremental,
              watch,
              codeEmbeddingSettings,
              memoryEmbeddingSettings);
      int failures = orchestrator.run(settings);
      if (failures > 0) {
        log.error("Ingestion finished with {} file failure(s).", failures);
        return 2;
      }
    }
    log.info("Ingestion complete for project '{}'.", project);
    return 0;
  }

  private boolean shouldInstallInstructions() {
    return initInstructions
        || withMemories
        || instructionsFile != null
        || noMemgraphIngesterMcp
        || optionWasMatched(Const.Cli.INSTRUCTIONS_AGENT);
  }

  private boolean isFollowOnCliActionRequested() {
    return checkJsRuntime
        || checkPythonRuntime
        || checkCtagsRuntime
        || sourceRoot != null
        || boltUrl != null
        || watch
        || applySchema
        || wipeAllData
        || wipeProjectCode
        || wipeProjectMemories
        || wipeCodeRag
        || wipeMemoryRag
        || incremental
        || optionWasMatched(Const.Cli.THREADS)
        || optionWasMatched(Const.Cli.THREADS_SHORT)
        || optionWasMatched(Const.Cli.USER)
        || optionWasMatched(Const.Cli.USER_SHORT)
        || optionWasMatched(Const.Cli.PASS)
        || optionWasMatched(Const.Cli.PASS_SHORT)
        || optionWasMatched(Const.Cli.CLASSPATH)
        || optionWasMatched(Const.Cli.JS_RUNTIME_MODE)
        || optionWasMatched(Const.Cli.JS_RUNTIME_CACHE)
        || optionWasMatched(Const.Cli.JS_NODE_VERSION)
        || optionWasMatched(Const.Cli.JS_TYPESCRIPT_VERSION)
        || optionWasMatched(Const.Cli.PYTHON_RUNTIME_MODE)
        || optionWasMatched(Const.Cli.PYTHON_RUNTIME_CACHE)
        || optionWasMatched(Const.Cli.PYTHON_VERSION)
        || optionWasMatched(Const.Cli.PYTHON_BUILD);
  }

  private boolean optionWasMatched(String optionName) {
    return commandSpec != null
        && commandSpec.commandLine() != null
        && commandSpec.commandLine().getParseResult() != null
        && commandSpec.commandLine().getParseResult().hasMatchedOption(optionName);
  }

  private Integer installAgentInstructions() {
    try {
      Path target =
          instructionsFile == null
              ? AgentInstructionsInstaller.defaultInstructionFile(instructionsAgent)
              : instructionsFile;
      io.github.ousatov.tools.memgraph.vo.cli.InstallResult result =
          AgentInstructionsInstaller.install(target, project, withMemories, noMemgraphIngesterMcp);
      log.info(
          "Updated Memgraph instructions in {} with project '{}' (memories: {}).",
          result.target(),
          project,
          result.includeMemories());
      return 0;
    } catch (IllegalArgumentException | IllegalStateException | IOException e) {
      log.error(e.getMessage());
      return 1;
    }
  }

  private List<LanguageAdapter<?>> createLanguageAdapters(
      RuntimeMode selectedJsRuntimeMode,
      RuntimeMode selectedPythonRuntimeMode,
      RuntimeMode selectedCtagsRuntimeMode) {
    return LanguageAdapterFactory.create(
        sourceRoot,
        classpath,
        new JsRuntimeOptions(
            resolvedJsRuntimeCache(), jsNodeVersion, jsTypescriptVersion, selectedJsRuntimeMode),
        new PythonRuntimeOptions(
            resolvedPythonRuntimeCache(), pythonVersion, pythonBuild, selectedPythonRuntimeMode),
        new CtagsRuntimeOptions(
            resolvedCtagsRuntimeCache(), ctagsVersion, selectedCtagsRuntimeMode));
  }

  private Path resolvedJsRuntimeCache() {
    return jsRuntimeCache == null ? ManagedNodeRuntime.defaultCacheRoot() : jsRuntimeCache;
  }

  private Path resolvedPythonRuntimeCache() {
    return resolvePythonRuntimeCache(pythonRuntimeCache);
  }

  private Path resolvedCtagsRuntimeCache() {
    return ctagsRuntimeCache == null ? ManagedCtagsRuntime.defaultCacheRoot() : ctagsRuntimeCache;
  }

  static Path resolvePythonRuntimeCache(Path pythonRuntimeCache) {
    return pythonRuntimeCache == null
        ? ManagedPythonRuntime.defaultCacheRoot()
        : pythonRuntimeCache;
  }
}
