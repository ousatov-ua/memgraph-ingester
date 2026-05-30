package io.github.ousatov.tools.memgraph.exe.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ousatov.tools.memgraph.exe.analyze.ManagedPythonRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalyzer;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for Python source discovery.
 *
 * @author Oleksii Usatov
 */
class PythonLanguageAdapterTest {

  private final PythonLanguageAdapter adapter = new PythonLanguageAdapter(null);

  @TempDir private Path tempDir;

  @Test
  void acceptsPythonSourceAndStubFiles() {
    assertTrue(adapter.accepts(Path.of("src/app.py")));
    assertTrue(adapter.accepts(Path.of("src/app.pyi")));
  }

  @Test
  void rejectsGeneratedAndEnvironmentDirectories() {
    assertFalse(adapter.accepts(Path.of("__pycache__/app.py")));
    assertFalse(adapter.accepts(Path.of("node_modules/pkg/app.py")));
    assertFalse(adapter.accepts(Path.of(".venv/lib/python/site-packages/pkg/app.py")));
    assertFalse(adapter.accepts(Path.of("dist/app.py")));
    assertFalse(adapter.accepts(Path.of("target/classes/app.py")));
  }

  @Test
  void discoverFilesSkipsVirtualEnvironmentAndCacheSubtrees() throws IOException {
    Path appFile = tempDir.resolve("src/app.py");
    Path dependencyFile = tempDir.resolve(".venv/lib/site-packages/pkg/app.py");
    Path cacheFile = tempDir.resolve("src/__pycache__/app.py");
    Files.createDirectories(appFile.getParent());
    Files.createDirectories(dependencyFile.getParent());
    Files.createDirectories(cacheFile.getParent());
    Files.writeString(appFile, "value = 1\n");
    Files.writeString(dependencyFile, "value = 2\n");
    Files.writeString(cacheFile, "value = 3\n");

    assertIterableEquals(List.of(appFile), adapter.discoverFiles(tempDir));
  }

  @Test
  void discoverFilesSkipsTargetSubtree() throws IOException {
    Path appFile = tempDir.resolve("src/app.py");
    Path targetFile = tempDir.resolve("target/classes/app.py");
    Files.createDirectories(appFile.getParent());
    Files.createDirectories(targetFile.getParent());
    Files.writeString(appFile, "value = 1\n");
    Files.writeString(targetFile, "value = 2\n");

    assertIterableEquals(List.of(appFile), adapter.discoverFiles(tempDir));
  }

  @Test
  void discoverFilesIgnoresSkippedNamesOutsideSourceRoot() throws IOException {
    Path sourceRoot = tempDir.resolve("build/project");
    Path appFile = sourceRoot.resolve("src/app.py");
    Path cacheFile = sourceRoot.resolve("src/__pycache__/app.py");
    Files.createDirectories(appFile.getParent());
    Files.createDirectories(cacheFile.getParent());
    Files.writeString(appFile, "value = 1\n");
    Files.writeString(cacheFile, "value = 2\n");

    assertIterableEquals(List.of(appFile), adapter.discoverFiles(sourceRoot));
  }

  @Test
  void returnsEmptyWhenRuntimeFailureEscapesAnalyzer() throws IOException {
    assumeTrue(systemPythonAvailable(), "Python adapter parse-failure test requires python3");
    Path brokenSource = tempDir.resolve("broken.py");
    Files.writeString(brokenSource, "def broken(:\n");
    PythonLanguageAdapter parsingAdapter =
        new PythonLanguageAdapter(
            new PythonAnalyzer(
                tempDir,
                new ManagedPythonRuntime(
                    tempDir.resolve("runtime"),
                    ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
                    ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
                    RuntimeMode.SYSTEM)));

    assertTrue(parsingAdapter.parse(brokenSource).isEmpty());
  }

  private static boolean systemPythonAvailable() {
    try {
      Process process =
          new ProcessBuilder(ManagedPythonRuntime.systemPythonExecutable(), "--version")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException _) {
      return false;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
