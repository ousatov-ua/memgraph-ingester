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
 * Unit tests for managed Node.js cache readiness behavior.
 *
 * @author Oleksii Usatov
 */
class ManagedNodeRuntimeTest {

  @TempDir private Path tempDir;

  @Test
  void offlineRuntimeMarksExistingExecutableReady() throws IOException {
    Path installDir =
        tempDir
            .resolve("node")
            .resolve(ManagedNodeRuntime.DEFAULT_NODE_VERSION)
            .resolve(platformId());
    Path executable = installDir.resolve(isWindows() ? "node.exe" : "bin/node");
    Files.createDirectories(executable.getParent());
    Files.writeString(executable, "");
    assertTrue(executable.toFile().setExecutable(true, true) || isWindows());

    ManagedNodeRuntime runtime =
        new ManagedNodeRuntime(
            tempDir, "v" + ManagedNodeRuntime.DEFAULT_NODE_VERSION, RuntimeMode.OFFLINE);

    assertEquals(executable, runtime.nodeExecutable());
    assertTrue(Files.isRegularFile(installDir.resolve(".install-complete")));
  }

  @Test
  void platformCurrentAllowsArm64RuntimeIds() throws IOException {
    assertEquals(
        cachedExecutable("Linux", "linux-arm64"),
        offlineExecutableFor("Linux", "aarch64", "linux-arm64"));
    assertEquals(
        cachedExecutable("Windows 11", "win-arm64"),
        offlineExecutableFor("Windows 11", "arm64", "win-arm64"));
  }

  @Test
  void platformCurrentRejectsUnsupportedUnixRuntimeIds() {
    String originalOsName = System.getProperty("os.name");
    String originalArchName = System.getProperty("os.arch");
    try {
      System.setProperty("os.name", "FreeBSD");
      System.setProperty("os.arch", "amd64");
      ManagedNodeRuntime runtime =
          new ManagedNodeRuntime(
              tempDir, "v" + ManagedNodeRuntime.DEFAULT_NODE_VERSION, RuntimeMode.OFFLINE);

      ProcessingException exception =
          assertThrows(ProcessingException.class, runtime::nodeExecutable);

      assertTrue(exception.getMessage().contains("Unsupported operating system"));
    } finally {
      restoreSystemProperty("os.name", originalOsName);
      restoreSystemProperty("os.arch", originalArchName);
    }
  }

  private static String platformId() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    String os = osName.contains("win") ? "win" : nixOs(osName);
    String arch =
        switch (archName) {
          case "aarch64", "arm64" -> "arm64";
          case "amd64", "x86_64" -> "x64";
          default -> "";
        };
    assumeTrue(!arch.isBlank(), "Managed Node.js test requires a supported CPU architecture");
    return os + "-" + arch;
  }

  private Path offlineExecutableFor(String osName, String archName, String platformId)
      throws IOException {
    String originalOsName = System.getProperty("os.name");
    String originalArchName = System.getProperty("os.arch");
    try {
      System.setProperty("os.name", osName);
      System.setProperty("os.arch", archName);
      Path executable = cachedExecutable(osName, platformId);
      Files.createDirectories(executable.getParent());
      Files.writeString(executable, "");
      assertTrue(executable.toFile().setExecutable(true, true) || isWindowsName(osName));
      ManagedNodeRuntime runtime =
          new ManagedNodeRuntime(
              tempDir, "v" + ManagedNodeRuntime.DEFAULT_NODE_VERSION, RuntimeMode.OFFLINE);
      return runtime.nodeExecutable();
    } finally {
      restoreSystemProperty("os.name", originalOsName);
      restoreSystemProperty("os.arch", originalArchName);
    }
  }

  private Path cachedExecutable(String osName, String platformId) {
    Path installDir =
        tempDir
            .resolve("node")
            .resolve(ManagedNodeRuntime.DEFAULT_NODE_VERSION)
            .resolve(platformId);
    return installDir.resolve(isWindowsName(osName) ? "node.exe" : "bin/node");
  }

  private static void restoreSystemProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
      return;
    }
    System.setProperty(key, value);
  }

  private static String nixOs(String osName) {
    return osName.contains("mac") || osName.contains("darwin") ? "darwin" : "linux";
  }

  private static boolean isWindows() {
    return isWindowsName(System.getProperty("os.name", ""));
  }

  private static boolean isWindowsName(String osName) {
    return osName.toLowerCase(Locale.ROOT).contains("win");
  }
}
