package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.vo.analysis.JsAnalysis;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Invokes the bundled Node.js helper and converts its NDJSON output into neutral records.
 *
 * @author Oleksii Usatov
 */
public final class JsAnalyzer {

  private static final Logger log = LoggerFactory.getLogger(JsAnalyzer.class);
  private static final String HELPER_RESOURCE_DIR = "/io/github/ousatov/tools/memgraph/js/";
  private static final String HELPER_SCRIPT_NAME = "js-analyzer.cjs";
  private static final List<String> HELPER_RESOURCE_NAMES =
      List.of(HELPER_SCRIPT_NAME, "js-analyzer-ast.cjs", "js-analyzer-paths.cjs");
  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);

  private final Path sourceRoot;
  private final ManagedNodeRuntime nodeRuntime;
  private final ManagedTypescriptPackage typescriptPackage;
  private final Path helperScript;

  public JsAnalyzer(
      Path sourceRoot, ManagedNodeRuntime nodeRuntime, ManagedTypescriptPackage typescriptPackage) {
    this(sourceRoot, nodeRuntime, typescriptPackage, extractHelperScript());
  }

  private JsAnalyzer(
      Path sourceRoot,
      ManagedNodeRuntime nodeRuntime,
      ManagedTypescriptPackage typescriptPackage,
      Path helperScript) {
    this.sourceRoot = sourceRoot;
    this.nodeRuntime = nodeRuntime;
    this.typescriptPackage = typescriptPackage;
    this.helperScript = helperScript;
  }

  private static CompletableFuture<String> readAsync(InputStream input, String streamName) {
    CompletableFuture<String> output = new CompletableFuture<>();
    Thread reader =
        new Thread(
            () -> {
              try (input) {
                output.complete(new String(input.readAllBytes(), StandardCharsets.UTF_8));
              } catch (IOException e) {
                output.completeExceptionally(e);
              }
            },
            "memgraph-js-analyzer-" + streamName);
    reader.setDaemon(true);
    reader.start();
    return output;
  }

  private static String outputOf(CompletableFuture<String> output, Path file, String streamName) {
    try {
      return output.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while reading analyzer " + streamName, e);
    } catch (ExecutionException e) {
      throw new ProcessingException(
          "Could not read JavaScript analyzer " + streamName + " for " + file, e.getCause());
    }
  }

  private static JsAnalysis parse(String stdout, Path file) {
    return ModuleAnalysisParser.parse(
        stdout,
        file,
        "JavaScript",
        FlatJsonObjectParser::parse,
        JsAnalysis::new,
        line -> log.debug("Ignoring unknown JavaScript analyzer record: {}", line));
  }

  private static Path extractHelperScript() {
    try {
      Path helperDir = Files.createTempDirectory("memgraph-ingester-js-analyzer-");
      helperDir.toFile().deleteOnExit();
      for (String resourceName : HELPER_RESOURCE_NAMES) {
        String resourcePath = HELPER_RESOURCE_DIR + resourceName;
        try (InputStream in = JsAnalyzer.class.getResourceAsStream(resourcePath)) {
          if (in == null) {
            throw new ProcessingException(resourcePath + " is missing from jar");
          }
          Path helper = helperDir.resolve(resourceName);
          Files.write(helper, in.readAllBytes());
          helper.toFile().deleteOnExit();
        }
      }
      return helperDir.resolve(HELPER_SCRIPT_NAME);
    } catch (IOException e) {
      throw new ProcessingException("Could not extract JavaScript analyzer helper", e);
    }
  }

  public JsAnalysis analyze(Path file) {
    Path node = nodeRuntime.nodeExecutable();
    Path nodeModules = typescriptPackage.nodeModulesDir();
    ProcessBuilder processBuilder =
        new ProcessBuilder(
            node.toString(),
            helperScript.toString(),
            Const.Cli.FILE,
            file.toString(),
            Const.Cli.ROOT,
            sourceRoot.toString());
    processBuilder.environment().put("NODE_PATH", nodeModules.toString());
    try {
      Process process = processBuilder.start();
      CompletableFuture<String> stdout = readAsync(process.getInputStream(), Const.Params.STDOUT);
      CompletableFuture<String> stderr = readAsync(process.getErrorStream(), Const.Params.STDERR);
      if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new ProcessingException("JavaScript analyzer timed out for " + file);
      }
      String stderrText = outputOf(stderr, file, Const.Params.STDERR);
      if (process.exitValue() != 0) {
        throw new ProcessingException(
            "JavaScript analyzer failed for "
                + file
                + Const.Symbols.COLON_SPACE
                + stderrText.trim());
      }
      return parse(outputOf(stdout, file, Const.Params.STDOUT), file);
    } catch (IOException e) {
      throw new ProcessingException("Could not run JavaScript analyzer for " + file, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while analyzing " + file, e);
    }
  }

  /** Resolves the managed Node.js and TypeScript resources before worker threads parse files. */
  public void prepareRuntime() {
    nodeRuntime.nodeExecutable();
    typescriptPackage.nodeModulesDir();
  }

  public JsAnalyzer withSourceRoot(Path sourceRoot) {
    return new JsAnalyzer(sourceRoot, nodeRuntime, typescriptPackage, helperScript);
  }
}
