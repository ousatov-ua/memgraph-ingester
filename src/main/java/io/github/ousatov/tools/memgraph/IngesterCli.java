package io.github.ousatov.tools.memgraph;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.adapter.JavaLanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.JsLanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.analyze.JsAnalysis;
import io.github.ousatov.tools.memgraph.exe.analyze.JsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedNodeRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedTypescriptPackage;
import io.github.ousatov.tools.memgraph.exe.analyze.ParseService;
import io.github.ousatov.tools.memgraph.exe.analyze.RuntimeMode;
import io.github.ousatov.tools.memgraph.exe.ingestion.IngestionOrchestrator;
import io.github.ousatov.tools.memgraph.vo.Settings;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
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
@Command(name = "ingest", mixinStandardHelpOptions = true, version = "1.0")
public final class IngesterCli implements Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(IngesterCli.class);
  private static final String EXPORT_CONST_VALUE_1 = "export const value = 1;\n";

  @Spec private CommandSpec commandSpec;

  @Option(
      names = {"-s", "--source"},
      description = "Root source directory (e.g. src/main/java)")
  @SuppressWarnings("unused")
  private Path sourceRoot;

  @Option(
      names = {"-b", "--bolt"},
      description = "Bolt URL, e.g. bolt://host:7687")
  @SuppressWarnings("unused")
  private String boltUrl;

  @Option(
      names = {"-u", "--user"},
      defaultValue = "")
  @SuppressWarnings("unused")
  private String user;

  @Option(
      names = {"-p", "--pass"},
      defaultValue = "")
  @SuppressWarnings("unused")
  private String pass;

  @Option(
      names = {"-P", "--project"},
      description =
          "Logical project name; namespaces all nodes so multiple "
              + "projects can share one Memgraph instance")
  @SuppressWarnings("unused")
  private String project;

  @Option(
      names = "--wipe-project-code",
      description = "Delete the code graph belonging to this project before ingesting")
  @SuppressWarnings("unused")
  private boolean wipeProjectCode;

  @Option(
      names = "--wipe-project-memories",
      description = "Delete the memory graph belonging to this project before ingesting")
  @SuppressWarnings("unused")
  private boolean wipeProjectMemories;

  @Option(
      names = {"-t", "--threads"},
      defaultValue = "1",
      description =
          "Number of parser threads. Each thread gets its own Bolt session. "
              + "Defaults to 1 (sequential). Values above the number of CPU cores rarely help "
              + "because Memgraph serializes writes internally.")
  @SuppressWarnings("unused")
  private int threads;

  @Option(
      names = {"--apply-schema"},
      description = "Apply schema on Memgraph")
  @SuppressWarnings("unused")
  private boolean applySchema;

  @Option(
      names = {"--wipe-all"},
      description = "Wipe all data from Memgraph")
  @SuppressWarnings("unused")
  private boolean wipeAllData;

  @Option(
      names = {"--incremental"},
      description = "Skip files whose last-modified timestamp matches the stored value")
  @SuppressWarnings("unused")
  private boolean incremental;

  @Option(
      names = {"-w", "--watch"},
      description = "Watch for changes in the source directory and automatically re-ingest")
  @SuppressWarnings("unused")
  private boolean watch;

  @Option(
      names = {"--classpath"},
      defaultValue = "",
      description =
          "Additional classpath entries (JARs) for symbol resolution, separated by "
              + "the platform path separator. Improves CALLS edge and type resolution coverage.")
  @SuppressWarnings("unused")
  private String classpath;

  @Option(
      names = {"--js-runtime-mode"},
      defaultValue = "managed",
      description =
          "JavaScript runtime mode: managed downloads Node.js, system uses node from PATH,"
              + " offline requires a warmed managed cache. Defaults to managed.")
  @SuppressWarnings("unused")
  private String jsRuntimeMode;

  @Option(
      names = {"--js-runtime-cache"},
      description = "Cache directory for managed Node.js and TypeScript downloads.")
  @SuppressWarnings("unused")
  private Path jsRuntimeCache;

  @Option(
      names = {"--js-node-version"},
      defaultValue = ManagedNodeRuntime.DEFAULT_NODE_VERSION,
      description = "Pinned Node.js version used for managed JavaScript parsing.")
  @SuppressWarnings("unused")
  private String jsNodeVersion;

  @Option(
      names = {"--js-typescript-version"},
      defaultValue = ManagedTypescriptPackage.DEFAULT_TYPESCRIPT_VERSION,
      description = "Pinned TypeScript compiler package used by the JavaScript analyzer.")
  @SuppressWarnings("unused")
  private String jsTypescriptVersion;

  @Option(
      names = {"--check-js-runtime"},
      description =
          "Download/cache the managed JavaScript parser runtime if needed and run a local "
              + "parser smoke check without connecting to Memgraph.")
  @SuppressWarnings("unused")
  private boolean checkJsRuntime;

  @Option(
      names = {"--init-instructions"},
      description =
          "Write or replace managed Memgraph agent instructions. Code guidance is included by "
              + "default; add --with-memories for Memory workflow guidance.")
  @SuppressWarnings("unused")
  private boolean initInstructions;

  @Option(
      names = {"--instructions-agent"},
      defaultValue = "codex",
      description =
          "Agent preset for instruction installation: codex, claude, gemini, github, or copilot. "
              + "Implies --init-instructions when explicitly provided. Defaults to codex.")
  @SuppressWarnings("unused")
  private String instructionsAgent;

  @Option(
      names = {"--instructions-file"},
      description =
          "Instruction file to update for instruction installation. Overrides --instructions-agent "
              + "and implies --init-instructions.")
  @SuppressWarnings("unused")
  private Path instructionsFile;

  @Option(
      names = {"--with-memories"},
      description =
          "Apply managed agent instructions with optional Memory workflow guidance. Uses the "
              + "default instructions agent unless --instructions-agent or --instructions-file is "
              + "provided.")
  @SuppressWarnings("unused")
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

    RuntimeMode selectedRuntimeMode;
    try {
      selectedRuntimeMode = RuntimeMode.parse(jsRuntimeMode);
    } catch (IllegalArgumentException e) {
      log.error(e.getMessage());
      return 1;
    }
    if (checkJsRuntime) {
      return runJsRuntimeCheck(selectedRuntimeMode);
    }
    if (threads < 1) {
      log.error("--threads must be >= 1 (got {})", threads);
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
    log.info("Using next classpath entries: {}", classpath);
    try (Driver driver = GraphDatabase.driver(boltUrl, AuthTokens.basic(user, pass))) {
      List<LanguageAdapter> languageAdapters = createLanguageAdapters(selectedRuntimeMode);
      log.info(
          "Enabled source language adapters: {}",
          languageAdapters.stream().map(LanguageAdapter::displayName).toList());
      IngestionOrchestrator orchestrator =
          new IngestionOrchestrator(sourceRoot, project, threads, driver, languageAdapters);
      var settings =
          new Settings(
              wipeAllData, applySchema, wipeProjectCode, wipeProjectMemories, incremental, watch);
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
        || optionWasMatched("--instructions-agent");
  }

  private boolean isFollowOnCliActionRequested() {
    return checkJsRuntime
        || sourceRoot != null
        || boltUrl != null
        || watch
        || applySchema
        || wipeAllData
        || wipeProjectCode
        || wipeProjectMemories
        || incremental
        || optionWasMatched("--threads")
        || optionWasMatched("-t")
        || optionWasMatched("--user")
        || optionWasMatched("-u")
        || optionWasMatched("--pass")
        || optionWasMatched("-p")
        || optionWasMatched("--classpath")
        || optionWasMatched("--js-runtime-mode")
        || optionWasMatched("--js-runtime-cache")
        || optionWasMatched("--js-node-version")
        || optionWasMatched("--js-typescript-version");
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
      AgentInstructionsInstaller.InstallResult result =
          AgentInstructionsInstaller.install(target, project, withMemories);
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

  private Integer runJsRuntimeCheck(RuntimeMode selectedRuntimeMode) {
    Path cacheRoot =
        jsRuntimeCache == null ? ManagedNodeRuntime.defaultCacheRoot() : jsRuntimeCache;
    Path tempDir = null;
    try {
      tempDir = Files.createTempDirectory("memgraph-ingester-js-runtime-check-");
      Path defaultClass = tempDir.resolve("default-class.js");
      Path defaultFunction = tempDir.resolve("default-function.js");
      Path indexJs = tempDir.resolve("index.js");
      Path indexTs = tempDir.resolve("index.ts");
      Path hyphenModule = tempDir.resolve("my-file.js");
      Path underscoreModule = tempDir.resolve("my_file.js");
      Path dottedModule = tempDir.resolve("a.b.js");
      Path underscoredDottedModule = tempDir.resolve("a_b.js");
      Path constructorCall = tempDir.resolve("constructor-call.js");
      Path uninitializedVariable = tempDir.resolve("uninitialized-variable.ts");
      Path tsconfigBase = tempDir.resolve("tsconfig.base.json");
      Path tsconfig = tempDir.resolve("tsconfig.json");
      Path aliasConsumer = tempDir.resolve("alias-consumer.ts");
      Path specificAliasTarget = tempDir.resolve("src/app/specific.ts");
      Path broadAliasTarget = tempDir.resolve("src/shared/specific.ts");
      Files.writeString(
          defaultClass,
          """
          export default class {
            constructor(value) {
              this.value = value;
            }
          }
          """);
      Files.writeString(
          defaultFunction,
          """
          export default function(value) {
            return value;
          }
          """);
      Files.writeString(indexJs, "export const jsValue = 1;\n");
      Files.writeString(indexTs, "export const tsValue: number = 1;\n");
      Files.writeString(hyphenModule, EXPORT_CONST_VALUE_1);
      Files.writeString(underscoreModule, EXPORT_CONST_VALUE_1);
      Files.writeString(dottedModule, EXPORT_CONST_VALUE_1);
      Files.writeString(underscoredDottedModule, EXPORT_CONST_VALUE_1);
      Files.writeString(
          constructorCall,
          """
          class Service {
            constructor(value) {
              this.value = value;
            }
          }

          export function make() {
            return new Service(1);
          }
          """);
      Files.writeString(
          uninitializedVariable,
          """
          let deferredValue: string;

          export function value() {
            return deferredValue;
          }
          """);
      Files.writeString(
          tsconfigBase,
          """
          {
            "compilerOptions": {
              "baseUrl": ".",
              "paths": {
                "@/*": ["src/shared/*"],
                "@app/*": ["src/app/*"]
              }
            }
          }
          """);
      Files.writeString(
          tsconfig,
          """
          {
            "extends": "./tsconfig.base.json"
          }
          """);
      Files.createDirectories(specificAliasTarget.getParent());
      Files.createDirectories(broadAliasTarget.getParent());
      Files.writeString(specificAliasTarget, "export class Base {}\n");
      Files.writeString(broadAliasTarget, "export class Base {}\n");
      Files.writeString(
          aliasConsumer,
          """
          import { Base } from '@app/specific';

          export class Derived extends Base {}
          """);

      JsAnalyzer analyzer =
          new JsAnalyzer(
              tempDir,
              new ManagedNodeRuntime(cacheRoot, jsNodeVersion, selectedRuntimeMode),
              new ManagedTypescriptPackage(cacheRoot, jsTypescriptVersion, selectedRuntimeMode));
      assertDefaultClass(analyzer.analyze(defaultClass));
      assertDefaultFunction(analyzer.analyze(defaultFunction));
      assertDistinctModuleFqns(analyzer.analyze(indexJs), analyzer.analyze(indexTs));
      assertDistinctModuleFqns(
          analyzer.analyze(hyphenModule),
          analyzer.analyze(underscoreModule),
          "hyphen and underscore module FQNs");
      assertDistinctModuleFqns(
          analyzer.analyze(dottedModule),
          analyzer.analyze(underscoredDottedModule),
          "dotted and underscored module FQNs");
      assertConstructorCall(analyzer.analyze(constructorCall));
      assertTsconfigPathAlias(analyzer.analyze(aliasConsumer));
      analyzer.analyze(uninitializedVariable);
      log.info("JavaScript parser runtime check succeeded using cache {}", cacheRoot);
      return 0;
    } catch (IOException | RuntimeException e) {
      log.error("JavaScript parser runtime check failed: {}", e.getMessage());
      return 1;
    } finally {
      if (tempDir != null) {
        deleteDir(tempDir);
      }
    }
  }

  private static void assertDistinctModuleFqns(JsAnalysis jsAnalysis, JsAnalysis tsAnalysis) {
    assertDistinctModuleFqns(jsAnalysis, tsAnalysis, "JavaScript and TypeScript module FQNs");
  }

  private static void assertDistinctModuleFqns(
      JsAnalysis jsAnalysis, JsAnalysis tsAnalysis, String description) {
    if (jsAnalysis.moduleFqn().equals(tsAnalysis.moduleFqn())) {
      throw new ProcessingException(description + " must be distinct");
    }
  }

  private static void assertConstructorCall(JsAnalysis analysis) {
    boolean constructorCallFound =
        analysis.calls().stream()
            .anyMatch(
                call ->
                    call.callerSignature().endsWith(".make()")
                        && call.calleeSignature().endsWith(".Service.<init>(any)"));
    if (!constructorCallFound) {
      throw new ProcessingException("JavaScript constructor call was not parsed correctly");
    }
  }

  private static void assertTsconfigPathAlias(JsAnalysis analysis) {
    boolean specificAliasFound =
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    "classExtends".equals(relation.kind())
                        && relation.childFqn().endsWith(".alias$2d$consumer$2e$ts.Derived")
                        && "js.src.app.specific$2e$ts.Base".equals(relation.targetFqn()));
    if (!specificAliasFound) {
      throw new ProcessingException(
          "TypeScript extended tsconfig path alias was not resolved to the most specific target");
    }
  }

  private static void assertDefaultClass(JsAnalysis analysis) {
    boolean defaultClassFound =
        analysis.types().stream()
            .anyMatch(
                type ->
                    "class".equals(type.kind())
                        && "default".equals(type.name())
                        && type.hasConstructor());
    boolean constructorFound =
        analysis.members().stream()
            .anyMatch(
                member ->
                    "method".equals(member.memberType())
                        && "constructor".equals(member.kind())
                        && "<init>".equals(member.name()));
    if (!defaultClassFound || !constructorFound) {
      throw new ProcessingException("Default anonymous class was not parsed correctly");
    }
  }

  private static void assertDefaultFunction(JsAnalysis analysis) {
    boolean defaultFunctionFound =
        analysis.members().stream()
            .anyMatch(
                member ->
                    "method".equals(member.memberType())
                        && "function".equals(member.kind())
                        && "default".equals(member.name()));
    if (!defaultFunctionFound) {
      throw new ProcessingException("Default anonymous function was not parsed correctly");
    }
  }

  private static void deleteDir(Path root) {
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  log.warn("Could not delete temporary path {}: {}", path, e.getMessage());
                }
              });
    } catch (IOException e) {
      log.warn("Could not clean temporary directory {}: {}", root, e.getMessage());
    }
  }

  private List<LanguageAdapter> createLanguageAdapters(RuntimeMode selectedRuntimeMode) {
    List<Path> cpEntries =
        (classpath == null || classpath.isBlank())
            ? List.of()
            : Arrays.stream(classpath.split(File.pathSeparator))
                .map(Path::of)
                .filter(Files::isRegularFile)
                .toList();
    ParseService parseService = new ParseService(sourceRoot, cpEntries);
    Path cacheRoot =
        jsRuntimeCache == null ? ManagedNodeRuntime.defaultCacheRoot() : jsRuntimeCache;
    ManagedNodeRuntime nodeRuntime =
        new ManagedNodeRuntime(cacheRoot, jsNodeVersion, selectedRuntimeMode);
    ManagedTypescriptPackage typescriptPackage =
        new ManagedTypescriptPackage(cacheRoot, jsTypescriptVersion, selectedRuntimeMode);
    return List.of(
        new JavaLanguageAdapter(parseService),
        new JsLanguageAdapter(new JsAnalyzer(sourceRoot, nodeRuntime, typescriptPackage)));
  }
}
