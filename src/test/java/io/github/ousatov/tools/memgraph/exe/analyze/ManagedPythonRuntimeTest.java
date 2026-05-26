package io.github.ousatov.tools.memgraph.exe.analyze;

import static io.github.ousatov.tools.memgraph.def.Const.SystemParams.AARCH_64;
import static io.github.ousatov.tools.memgraph.def.Const.SystemParams.AMD_64;
import static io.github.ousatov.tools.memgraph.def.Const.SystemParams.APPLE_DARWIN;
import static io.github.ousatov.tools.memgraph.def.Const.SystemParams.ARM_64;
import static io.github.ousatov.tools.memgraph.def.Const.SystemParams.DARWIN;
import static io.github.ousatov.tools.memgraph.def.Const.SystemParams.LINUX;
import static io.github.ousatov.tools.memgraph.def.Const.SystemParams.PYTHON;
import static io.github.ousatov.tools.memgraph.def.Const.SystemParams.WINDOWS;
import static io.github.ousatov.tools.memgraph.def.Const.SystemParams.X_86_64;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
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

  @Test
  void extractTgzPreservesRelativeSymlinkEntries() throws IOException {
    assumeSymlinkSupport();
    byte[] archive = archiveWithPythonSymlink("python3.14");

    ManagedPythonRuntime.extractTgz(archive, tempDir);

    Path python = tempDir.resolve("bin/python");
    assertTrue(Files.isSymbolicLink(python));
    assertEquals(Path.of("python3.14"), Files.readSymbolicLink(python));
    assertEquals("print('ok')", Files.readString(python));
  }

  @Test
  void extractTgzRejectsEscapingSymlinkEntries() throws IOException {
    byte[] archive = archiveWithPythonSymlink("../../outside");

    ProcessingException error =
        assertThrows(
            ProcessingException.class, () -> ManagedPythonRuntime.extractTgz(archive, tempDir));

    assertTrue(error.getMessage().contains("escapes CPython cache"));
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
            platformId.contains("pc-windows-msvc") ? "python.exe" : "bin/" + pythonBinaryName());
  }

  private Path venvExecutable(String platformId) {
    return venvDir(platformId)
        .resolve(platformId.contains("pc-windows-msvc") ? "Scripts/python.exe" : "bin/python");
  }

  private Path standaloneInstallDir(String platformId) {
    return tempDir
        .resolve(PYTHON)
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

  private static byte[] archiveWithPythonSymlink(String linkTarget) throws IOException {
    byte[] content = "print('ok')".getBytes(StandardCharsets.UTF_8);
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(bytes);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      addDirectory(tar, "cpython/");
      addDirectory(tar, "cpython/bin/");
      addFile(tar, "cpython/bin/python3.14", content);
      addSymlink(tar, "cpython/bin/python", linkTarget);
      tar.finish();
      gzip.finish();
      return bytes.toByteArray();
    }
  }

  private static void addDirectory(TarArchiveOutputStream tar, String name) throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    tar.putArchiveEntry(entry);
    tar.closeArchiveEntry();
  }

  private static void addFile(TarArchiveOutputStream tar, String name, byte[] content)
      throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(content.length);
    tar.putArchiveEntry(entry);
    tar.write(content);
    tar.closeArchiveEntry();
  }

  private static void addSymlink(TarArchiveOutputStream tar, String name, String linkTarget)
      throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
    entry.setLinkName(linkTarget);
    tar.putArchiveEntry(entry);
    tar.closeArchiveEntry();
  }

  private void assumeSymlinkSupport() throws IOException {
    Path target = tempDir.resolve("symlink-target");
    Path link = tempDir.resolve("symlink-check");
    Files.writeString(target, "");
    boolean symlinkCreated = false;
    try {
      Files.createSymbolicLink(link, target.getFileName());
      symlinkCreated = true;
    } catch (UnsupportedOperationException e) {
      symlinkCreated = false;
    } catch (IOException e) {
      symlinkCreated = false;
    } finally {
      Files.deleteIfExists(link);
    }
    assumeTrue(symlinkCreated, "Symbolic links are not available");
  }

  private static String platformId() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    String os =
        switch (platformOs(osName)) {
          case DARWIN -> APPLE_DARWIN;
          case LINUX -> LINUX;
          case WINDOWS -> WINDOWS;
          default -> "";
        };
    String arch =
        switch (archName) {
          case AARCH_64, ARM_64 -> AARCH_64;
          case AMD_64, X_86_64 -> X_86_64;
          default -> "";
        };
    assumeTrue(!os.isBlank(), "Managed Python test requires a supported operating system");
    assumeTrue(!arch.isBlank(), "Managed Python test requires a supported CPU architecture");
    return arch + "-" + os;
  }

  private static String platformOs(String osName) {
    if (osName.contains("mac") || osName.contains("darwin")) {
      return DARWIN;
    }
    if (osName.contains("linux")) {
      return LINUX;
    }
    if (osName.contains("win")) {
      return WINDOWS;
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
    return PYTHON + ManagedPythonRuntime.DEFAULT_PYTHON_VERSION.substring(0, secondDot);
  }
}
