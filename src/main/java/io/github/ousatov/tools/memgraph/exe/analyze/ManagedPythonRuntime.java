package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.SystemParams;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads, verifies, caches, and exposes the CPython executable owned by this ingester.
 *
 * @author Oleksii Usatov
 */
public final class ManagedPythonRuntime extends ManagedHttpInstaller {

  public static final String DEFAULT_PYTHON_VERSION = "3.14.5";
  public static final String DEFAULT_PYTHON_BUILD = "20260510";

  private static final Logger log = LoggerFactory.getLogger(ManagedPythonRuntime.class);
  private static final String PYTHON_DIST =
      "https://github.com/astral-sh/python-build-standalone/releases/download/";
  private static final Duration VENV_TIMEOUT = Duration.ofMinutes(2);
  private static final String INSTALL_LOCK_FILE = Const.Files.INSTALL_LOCK;
  private static final String INSTALL_READY_FILE = Const.Files.INSTALL_COMPLETE;
  private static final String VENV_READY_FILE = ".venv-complete";
  private static final ConcurrentMap<Path, Object> INSTALL_LOCKS = new ConcurrentHashMap<>();

  private final Path cacheRoot;
  private final String pythonVersion;
  private final String pythonBuild;
  private final RuntimeMode runtimeMode;

  public ManagedPythonRuntime(
      Path cacheRoot, String pythonVersion, String pythonBuild, RuntimeMode runtimeMode) {
    super(true);
    this.cacheRoot = Objects.requireNonNull(cacheRoot, Const.Params.CACHE_ROOT);
    this.pythonVersion = normalizeVersion(pythonVersion);
    this.pythonBuild = normalizeBuild(pythonBuild);
    this.runtimeMode = Objects.requireNonNull(runtimeMode, Const.Params.RUNTIME_MODE);
  }

  public Path pythonExecutable() {
    if (runtimeMode == RuntimeMode.SYSTEM) {
      return Path.of(systemPythonExecutable());
    }
    ManagedRuntimePlatform platform = ManagedRuntimePlatform.current();
    Path installDir = installDir(platform);
    Path standaloneExecutable = standalonePythonExecutable(platform, installDir);
    if (!isStandalonePythonReady(standaloneExecutable, installDir)) {
      ensureStandalonePythonInstalled(platform, installDir, standaloneExecutable);
    }
    Path venvDir = venvDir(platform);
    Path venvExecutable = venvPythonExecutable(platform, venvDir);
    if (isManagedVenvReady(venvExecutable, venvDir)) {
      return venvExecutable;
    }
    ensureManagedVenvInstalled(standaloneExecutable, venvDir, venvExecutable);
    return venvExecutable;
  }

  public static Path defaultCacheRoot() {
    return ManagedNodeRuntime.defaultCacheRoot();
  }

  public static String systemPythonExecutable() {
    String configured = System.getenv("MEMGRAPH_INGESTER_PYTHON");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    return ManagedRuntimePlatform.isCurrentWindows() ? Const.Files.PYTHON_EXE : Const.Files.PYTHON3;
  }

  private void ensureStandalonePythonInstalled(
      ManagedRuntimePlatform platform, Path installDir, Path executable) {
    try {
      Files.createDirectories(installDir);
      Object localLock = INSTALL_LOCKS.computeIfAbsent(lockKey(installDir), _ -> new Object());
      synchronized (localLock) {
        try (FileChannel channel =
                FileChannel.open(
                    installDir.resolve(INSTALL_LOCK_FILE),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            var _ = channel.lock()) {
          if (isStandalonePythonReady(executable, installDir)) {
            return;
          }
          if (runtimeMode == RuntimeMode.OFFLINE && Files.isExecutable(executable)) {
            markStandalonePythonReady(installDir);
            return;
          }
          if (runtimeMode == RuntimeMode.OFFLINE) {
            throw new ProcessingException(
                "Managed CPython "
                    + pythonVersion
                    + Const.Symbols.PLUS
                    + pythonBuild
                    + " is not cached at "
                    + executable
                    + "; disable --python-runtime-mode=offline or pre-warm the cache.");
          }
          installStandalonePython(platform, installDir, executable);
        }
      }
    } catch (IOException e) {
      throw new ProcessingException(
          "Could not install managed CPython " + pythonVersion + Const.Symbols.PLUS + pythonBuild,
          e);
    }
  }

  private void ensureManagedVenvInstalled(
      Path standaloneExecutable, Path venvDir, Path executable) {
    try {
      Files.createDirectories(venvDir);
      Object localLock = INSTALL_LOCKS.computeIfAbsent(lockKey(venvDir), _ -> new Object());
      synchronized (localLock) {
        try (FileChannel channel =
                FileChannel.open(
                    venvDir.resolve(INSTALL_LOCK_FILE),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            var _ = channel.lock()) {
          if (isManagedVenvReady(executable, venvDir)) {
            return;
          }
          if (runtimeMode == RuntimeMode.OFFLINE && Files.isExecutable(executable)) {
            markManagedVenvReady(venvDir);
            return;
          }
          createManagedVenv(standaloneExecutable, venvDir, executable);
        }
      }
    } catch (IOException e) {
      throw new ProcessingException("Could not create managed Python venv at " + venvDir, e);
    }
  }

  private void installStandalonePython(
      ManagedRuntimePlatform platform, Path installDir, Path executable) throws IOException {
    String archiveName = pythonArchiveName(platform, pythonVersion, pythonBuild);
    URI archiveUri =
        URI.create(
            PYTHON_DIST + pythonBuild + Const.Symbols.SLASH + encodeArchiveName(archiveName));
    URI sumsUri = URI.create(PYTHON_DIST + pythonBuild + "/SHA256SUMS");
    ConsoleStatusLine.withFinishedLine(
        System.err,
        () ->
            log.atInfo()
                .setMessage("Checking managed CPython {}+{} ({}) availability")
                .addArgument(pythonVersion)
                .addArgument(pythonBuild)
                .addArgument(() -> pythonId(platform))
                .log());
    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.start(
            "CPython " + pythonVersion + Const.Symbols.PLUS + pythonBuild)) {
      byte[] archive = download(archiveUri);
      verifySha256(archive, archiveName, downloadText(sumsUri), "CPython");
      extractTgz(archive, installDir);
      if (!Files.isExecutable(executable)) {
        @SuppressWarnings(Const.Warnings.IGNORED_RETURN_VALUE)
        var _ = executable.toFile().setExecutable(true, true);
      }
      if (!Files.isExecutable(executable)) {
        throw new ProcessingException("Managed CPython executable was not created: " + executable);
      }
      markStandalonePythonReady(installDir);
      indicator.succeeded();
    }
  }

  private void createManagedVenv(Path standaloneExecutable, Path venvDir, Path executable) {
    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.start("Python virtual environment")) {
      ProcessBuilder processBuilder =
          new ProcessBuilder(
              standaloneExecutable.toString(), "-m", Const.Files.VENV, venvDir.toString());
      processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
      processBuilder.environment().put("PYTHONNOUSERSITE", Const.Params.ONE);
      Process process = processBuilder.start();
      if (!process.waitFor(VENV_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new ProcessingException("Creating managed Python venv timed out at " + venvDir);
      }
      if (process.exitValue() != 0) {
        throw new ProcessingException("Creating managed Python venv failed at " + venvDir);
      }
      if (!Files.isExecutable(executable)) {
        @SuppressWarnings(Const.Warnings.IGNORED_RETURN_VALUE)
        var _ = executable.toFile().setExecutable(true, true);
      }
      if (!Files.isExecutable(executable)) {
        throw new ProcessingException(
            "Managed Python venv executable was not created: " + executable);
      }
      markManagedVenvReady(venvDir);
      indicator.succeeded();
    } catch (IOException e) {
      throw new ProcessingException("Could not create managed Python venv at " + venvDir, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException(
          "Interrupted while creating managed Python venv at " + venvDir, e);
    }
  }

  private Path installDir(ManagedRuntimePlatform platform) {
    return cacheRoot
        .resolve(SystemParams.PYTHON)
        .resolve("standalone")
        .resolve(pythonVersion + Const.Symbols.PLUS + pythonBuild)
        .resolve(pythonId(platform));
  }

  private Path venvDir(ManagedRuntimePlatform platform) {
    return cacheRoot
        .resolve(SystemParams.PYTHON)
        .resolve(Const.Files.VENV)
        .resolve(pythonVersion + Const.Symbols.PLUS + pythonBuild)
        .resolve(pythonId(platform));
  }

  private Path standalonePythonExecutable(ManagedRuntimePlatform platform, Path installDir) {
    String executable = platform.isWindows() ? Const.Files.PYTHON_EXE : "bin/" + pythonBinaryName();
    return installDir.resolve(executable);
  }

  private Path venvPythonExecutable(ManagedRuntimePlatform platform, Path venvDir) {
    String executable = platform.isWindows() ? "Scripts/python.exe" : "bin/python";
    return venvDir.resolve(executable);
  }

  private boolean isStandalonePythonReady(Path executable, Path installDir) {
    return Files.isExecutable(executable)
        && Files.isRegularFile(installDir.resolve(INSTALL_READY_FILE));
  }

  private boolean isManagedVenvReady(Path executable, Path venvDir) {
    return Files.isExecutable(executable) && Files.isRegularFile(venvDir.resolve(VENV_READY_FILE));
  }

  private void markStandalonePythonReady(Path installDir) throws IOException {
    Files.writeString(
        installDir.resolve(INSTALL_READY_FILE),
        "cpython " + pythonVersion + Const.Symbols.PLUS + pythonBuild + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private void markManagedVenvReady(Path venvDir) throws IOException {
    Files.writeString(
        venvDir.resolve(VENV_READY_FILE),
        "cpython-venv " + pythonVersion + Const.Symbols.PLUS + pythonBuild + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  static void extractTgz(byte[] archive, Path installDir) throws IOException {
    Path installRoot = installDir.toAbsolutePath().normalize();
    try (InputStream raw = new ByteArrayInputStream(archive);
        InputStream gzip = new GZIPInputStream(raw);
        TarArchiveInputStream in = new TarArchiveInputStream(gzip)) {
      TarArchiveEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (!entry.isDirectory() && !entry.isFile() && !entry.isSymbolicLink()) {
          continue;
        }
        Path relative = stripTopDirectory(entry.getName());
        if (relative == null) {
          continue;
        }
        Path target = installRoot.resolve(relative).normalize();
        if (!target.startsWith(installRoot)) {
          throw new ProcessingException("Archive entry escapes CPython cache: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else if (entry.isSymbolicLink()) {
          extractSymbolicLink(entry, installRoot, target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static void extractSymbolicLink(TarArchiveEntry entry, Path installRoot, Path target)
      throws IOException {
    String rawLinkName = entry.getLinkName();
    if (rawLinkName == null || rawLinkName.isBlank()) {
      throw new ProcessingException("Archive symlink target is empty: " + entry.getName());
    }
    Path linkTarget = Path.of(rawLinkName);
    if (linkTarget.isAbsolute()) {
      throw new ProcessingException("Archive symlink target is absolute: " + entry.getName());
    }
    Path resolvedTarget = target.getParent().resolve(linkTarget).normalize();
    if (!resolvedTarget.startsWith(installRoot)) {
      throw new ProcessingException("Archive symlink escapes CPython cache: " + entry.getName());
    }
    Files.createDirectories(target.getParent());
    Files.deleteIfExists(target);
    Files.createSymbolicLink(target, linkTarget);
  }

  private static Path stripTopDirectory(String rawName) {
    String normalized = rawName.replace('\\', '/');
    int slash = normalized.indexOf('/');
    if (slash < 0 || slash == normalized.length() - 1) {
      return null;
    }
    return Path.of(normalized.substring(slash + 1));
  }

  private static String normalizeVersion(String version) {
    String normalized =
        version == null || version.isBlank() ? DEFAULT_PYTHON_VERSION : version.trim();
    return normalized.startsWith(Const.SystemParams.VERSION_PREFIX)
        ? normalized.substring(1)
        : normalized;
  }

  private static String normalizeBuild(String build) {
    return build == null || build.isBlank() ? DEFAULT_PYTHON_BUILD : build.trim();
  }

  private String pythonBinaryName() {
    int firstDot = pythonVersion.indexOf('.');
    int secondDot = pythonVersion.indexOf('.', firstDot + 1);
    if (firstDot < 0 || secondDot < 0) {
      return Const.Files.PYTHON3;
    }
    return Const.SystemParams.PYTHON + pythonVersion.substring(0, secondDot);
  }

  private static String encodeArchiveName(String archiveName) {
    return archiveName.replace(Const.Symbols.PLUS, "%2B");
  }

  private static String pythonArchiveName(
      ManagedRuntimePlatform platform, String version, String build) {
    return "cpython-"
        + version
        + Const.Symbols.PLUS
        + build
        + Const.Symbols.DASH
        + pythonId(platform)
        + "-install_only_stripped.tar.gz";
  }

  private static String pythonId(ManagedRuntimePlatform platform) {
    return pythonArch(platform) + Const.Symbols.DASH + pythonOs(platform);
  }

  private static String pythonOs(ManagedRuntimePlatform platform) {
    if (platform.isMacos()) {
      return SystemParams.APPLE_DARWIN;
    }
    if (platform.isLinux()) {
      return SystemParams.UNKNOWN_LINUX_GNU;
    }
    return "pc-windows-msvc";
  }

  private static String pythonArch(ManagedRuntimePlatform platform) {
    return switch (platform.arch()) {
      case SystemParams.ARM_64 -> SystemParams.AARCH_64;
      case SystemParams.X_86_64 -> SystemParams.X_86_64;
      default -> throw new ProcessingException("Unsupported CPU architecture: " + platform.arch());
    };
  }
}
