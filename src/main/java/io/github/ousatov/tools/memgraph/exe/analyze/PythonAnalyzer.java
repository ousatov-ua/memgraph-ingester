package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.vo.analysis.PythonAnalysis;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Invokes the bundled Python AST helper and converts its NDJSON output into neutral records.
 *
 * @author Oleksii Usatov
 */
public final class PythonAnalyzer {

  private static final Logger log = LoggerFactory.getLogger(PythonAnalyzer.class);
  private static final String HELPER_RESOURCE_DIR = "/io/github/ousatov/tools/memgraph/python/";
  private static final String HELPER_SCRIPT_NAME = "python-analyzer.py";
  private static final Duration PROCESS_TIMEOUT =
      AppConfig.durationValue("analyzers.python.process-timeout");

  private final Path sourceRoot;
  private final ManagedPythonRuntime pythonRuntime;
  private final Path helperScript;

  @SuppressWarnings("unused")
  public PythonAnalyzer(Path sourceRoot) {
    this(
        sourceRoot,
        new ManagedPythonRuntime(
            ManagedPythonRuntime.defaultCacheRoot(),
            ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
            ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
            RuntimeMode.MANAGED));
  }

  public PythonAnalyzer(Path sourceRoot, ManagedPythonRuntime pythonRuntime) {
    this(sourceRoot, pythonRuntime, extractHelperScript());
  }

  private PythonAnalyzer(Path sourceRoot, ManagedPythonRuntime pythonRuntime, Path helperScript) {
    this.sourceRoot = sourceRoot;
    this.pythonRuntime = pythonRuntime;
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
            "memgraph-python-analyzer-" + streamName);
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
          "Could not read Python analyzer " + streamName + " for " + file, e.getCause());
    }
  }

  private static PythonAnalysis parse(String stdout, Path file) {
    return ModuleAnalysisParser.parse(
        stdout,
        file,
        "Python",
        FlatJsonObjectParser::parsePythonAnalyzerOutput,
        PythonAnalysis::new,
        line -> log.debug("Ignoring unknown Python analyzer record: {}", line));
  }

  private static Path extractHelperScript() {
    String resourcePath = HELPER_RESOURCE_DIR + HELPER_SCRIPT_NAME;
    try (InputStream in = PythonAnalyzer.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new ProcessingException(resourcePath + " is missing from jar");
      }
      Path helperDir = Files.createTempDirectory("memgraph-ingester-python-analyzer-");
      helperDir.toFile().deleteOnExit();
      Path helper = helperDir.resolve(HELPER_SCRIPT_NAME);
      Files.write(helper, in.readAllBytes());
      helper.toFile().deleteOnExit();
      return helper;
    } catch (IOException e) {
      throw new ProcessingException("Could not extract Python analyzer helper", e);
    }
  }

  public PythonAnalysis analyze(Path file) {
    Path python = pythonRuntime.pythonExecutable();
    ProcessBuilder processBuilder =
        new ProcessBuilder(
            python.toString(),
            helperScript.toString(),
            Const.Cli.FILE,
            file.toString(),
            Const.Cli.ROOT,
            sourceRoot.toString());
    try {
      Process process = processBuilder.start();
      CompletableFuture<String> stdout = readAsync(process.getInputStream(), Const.Params.STDOUT);
      CompletableFuture<String> stderr = readAsync(process.getErrorStream(), Const.Params.STDERR);
      if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new ProcessingException("Python analyzer timed out for " + file);
      }
      String stderrText = outputOf(stderr, file, Const.Params.STDERR);
      if (process.exitValue() != 0) {
        throw new ProcessingException(
            "Python analyzer failed for " + file + Const.Symbols.COLON_SPACE + stderrText);
      }
      return parse(outputOf(stdout, file, Const.Params.STDOUT), file);
    } catch (IOException e) {
      throw new ProcessingException("Could not run Python analyzer for " + file, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while analyzing " + file, e);
    }
  }

  /** Resolves the managed CPython and virtual environment before worker threads parse files. */
  public void prepareRuntime() {
    pythonRuntime.pythonExecutable();
  }

  public PythonAnalyzer withSourceRoot(Path sourceRoot) {
    return new PythonAnalyzer(sourceRoot, pythonRuntime, helperScript);
  }
}
