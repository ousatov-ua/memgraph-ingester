package io.github.ousatov.tools.memgraph.exe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    assumeTrue(!os.equals("linux") || arch.equals("x64"), "Managed Node.js supports linux-x64");
    assumeTrue(!os.equals("win") || arch.equals("x64"), "Managed Node.js supports win-x64");
    return os + "-" + arch;
  }

  private static String nixOs(String osName) {
    return osName.contains("mac") || osName.contains("darwin") ? "darwin" : "linux";
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
