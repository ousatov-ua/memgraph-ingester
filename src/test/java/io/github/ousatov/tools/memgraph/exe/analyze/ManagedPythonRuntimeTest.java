package io.github.ousatov.tools.memgraph.exe.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for managed CPython cache and venv readiness behavior.
 *
 * @author Oleksii Usatov
 */
class ManagedPythonRuntimeTest {

  @TempDir private Path tempDir;

  @Test
  void offlineRuntimeMarksExistingStandaloneAndVenvReady() throws IOException {
    String platformId = platformId();
    Path standaloneExecutable = standaloneExecutable(platformId);
    Path venvExecutable = venvExecutable(platformId);
    createExecutable(standaloneExecutable);
    createExecutable(venvExecutable);

    ManagedPythonRuntime runtime =
        new ManagedPythonRuntime(
            tempDir,
            "v" + ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
            ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
            RuntimeMode.OFFLINE);

    assertEquals(venvExecutable, runtime.pythonExecutable());
    assertTrue(Files.isRegularFile(standaloneInstallDir(platformId).resolve(".install-complete")));
    assertTrue(Files.isRegularFile(venvDir(platformId).resolve(".venv-complete")));
  }

  @Test
  void platformCurrentAllowsArm64RuntimeIds() throws IOException {
    assertEquals(
        venvExecutable("aarch64-unknown-linux-gnu"),
        offlineExecutableFor("Linux", "aarch64", "aarch64-unknown-linux-gnu"));
    assertEquals(
        venvExecutable("aarch64-pc-windows-msvc"),
        offlineExecutableFor("Windows 11", "arm64", "aarch64-pc-windows-msvc"));
  }

  @Test
  void offlineRuntimeRejectsMissingStandalone() {
    ManagedPythonRuntime runtime =
        new ManagedPythonRuntime(
            tempDir,
            ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
            ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
            RuntimeMode.OFFLINE);

    ProcessingException error = assertThrows(ProcessingException.class, runtime::pythonExecutable);

    assertTrue(error.getMessage().contains("is not cached"));
  }

  private Path offlineExecutableFor(String osName, String archName, String platformId)
      throws IOException {
    String originalOsName = System.getProperty("os.name");
    String originalArchName = System.getProperty("os.arch");
    try {
      System.setProperty("os.name", osName);
      System.setProperty("os.arch", archName);
      Path standaloneExecutable = standaloneExecutable(platformId);
      Path venvExecutable = venvExecutable(platformId);
      createExecutable(standaloneExecutable);
      createExecutable(venvExecutable);
      ManagedPythonRuntime runtime =
          new ManagedPythonRuntime(
              tempDir,
              "v" + ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
              ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
              RuntimeMode.OFFLINE);
      return runtime.pythonExecutable();
    } finally {
      restoreSystemProperty("os.name", originalOsName);
      restoreSystemProperty("os.arch", originalArchName);
    }
  }

  private Path standaloneExecutable(String platformId) {
    return standaloneInstallDir(platformId)
        .resolve(
            platformId.contains("pc-windows-msvc")
                ? "python.exe"
                : "bin/" + pythonBinaryName());
  }

  private Path venvExecutable(String platformId) {
    return venvDir(platformId)
        .resolve(platformId.contains("pc-windows-msvc") ? "Scripts/python.exe" : "bin/python");
  }

  private Path standaloneInstallDir(String platformId) {
    return tempDir
        .resolve("python")
        .resolve("standalone")
        .resolve(
            ManagedPythonRuntime.DEFAULT_PYTHON_VERSION
                + "+"
                + ManagedPythonRuntime.DEFAULT_PYTHON_BUILD)
        .resolve(platformId);
  }

  private Path venvDir(String platformId) {
    return tempDir
        .resolve("python")
        .resolve("venv")
        .resolve(
            ManagedPythonRuntime.DEFAULT_PYTHON_VERSION
                + "+"
                + ManagedPythonRuntime.DEFAULT_PYTHON_BUILD)
        .resolve(platformId);
  }

  private static void createExecutable(Path executable) throws IOException {
    Files.createDirectories(executable.getParent());
    Files.writeString(executable, "");
    assertTrue(executable.toFile().setExecutable(true, true) || isWindows());
  }

  private static String platformId() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    String os =
        switch (platformOs(osName)) {
          case "darwin" -> "apple-darwin";
          case "linux" -> "unknown-linux-gnu";
          case "windows" -> "pc-windows-msvc";
          default -> "";
        };
    String arch =
        switch (archName) {
          case "aarch64", "arm64" -> "aarch64";
          case "amd64", "x86_64" -> "x86_64";
          default -> "";
        };
    assumeTrue(!os.isBlank(), "Managed Python test requires a supported operating system");
    assumeTrue(!arch.isBlank(), "Managed Python test requires a supported CPU architecture");
    return arch + "-" + os;
  }

  private static String platformOs(String osName) {
    if (osName.contains("mac") || osName.contains("darwin")) {
      return "darwin";
    }
    if (osName.contains("linux")) {
      return "linux";
    }
    if (osName.contains("win")) {
      return "windows";
    }
    return osName;
  }

  private static void restoreSystemProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
      return;
    }
    System.setProperty(key, value);
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static String pythonBinaryName() {
    int firstDot = ManagedPythonRuntime.DEFAULT_PYTHON_VERSION.indexOf('.');
    int secondDot = ManagedPythonRuntime.DEFAULT_PYTHON_VERSION.indexOf('.', firstDot + 1);
    return "python" + ManagedPythonRuntime.DEFAULT_PYTHON_VERSION.substring(0, secondDot);
  }
}
