package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const.SystemParams;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
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
public final class ManagedPythonRuntime {

  public static final String DEFAULT_PYTHON_VERSION = "3.14.5";
  public static final String DEFAULT_PYTHON_BUILD = "20260510";

  private static final Logger log = LoggerFactory.getLogger(ManagedPythonRuntime.class);
  private static final String PYTHON_DIST =
      "https://github.com/astral-sh/python-build-standalone/releases/download/";
  private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration VENV_TIMEOUT = Duration.ofMinutes(2);
  private static final String INSTALL_LOCK_FILE = ".install.lock";
  private static final String INSTALL_READY_FILE = ".install-complete";
  private static final String VENV_READY_FILE = ".venv-complete";
  private static final ConcurrentMap<Path, Object> INSTALL_LOCKS = new ConcurrentHashMap<>();

  private final Path cacheRoot;
  private final String pythonVersion;
  private final String pythonBuild;
  private final RuntimeMode runtimeMode;
  private final HttpClient http;

  public ManagedPythonRuntime(
      Path cacheRoot, String pythonVersion, String pythonBuild, RuntimeMode runtimeMode) {
    this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
    this.pythonVersion = normalizeVersion(pythonVersion);
    this.pythonBuild = normalizeBuild(pythonBuild);
    this.runtimeMode = Objects.requireNonNull(runtimeMode, "runtimeMode");
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  public Path pythonExecutable() {
    if (runtimeMode == RuntimeMode.SYSTEM) {
      return Path.of(systemPythonExecutable());
    }
    Platform platform = Platform.current();
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
    return isWindows() ? "python.exe" : "python3";
  }

  private void ensureStandalonePythonInstalled(
      Platform platform, Path installDir, Path executable) {
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
                    + "+"
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
          "Could not install managed CPython " + pythonVersion + "+" + pythonBuild, e);
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

  private void installStandalonePython(Platform platform, Path installDir, Path executable)
      throws IOException {
    String archiveName = platform.archiveName(pythonVersion, pythonBuild);
    URI archiveUri = URI.create(PYTHON_DIST + pythonBuild + "/" + encodeArchiveName(archiveName));
    URI sumsUri = URI.create(PYTHON_DIST + pythonBuild + "/SHA256SUMS");
    log.atInfo()
        .setMessage("Checking managed CPython {}+{} ({}) availability")
        .addArgument(pythonVersion)
        .addArgument(pythonBuild)
        .addArgument(platform::id)
        .log();
    byte[] archive = download(archiveUri);
    verifySha256(archive, archiveName, downloadText(sumsUri));
    extractTgz(archive, installDir);
    if (!Files.isExecutable(executable)) {
      @SuppressWarnings("java:S899")
      var _ = executable.toFile().setExecutable(true, true);
    }
    if (!Files.isExecutable(executable)) {
      throw new ProcessingException("Managed CPython executable was not created: " + executable);
    }
    markStandalonePythonReady(installDir);
  }

  private void createManagedVenv(Path standaloneExecutable, Path venvDir, Path executable) {
    try {
      ProcessBuilder processBuilder =
          new ProcessBuilder(standaloneExecutable.toString(), "-m", "venv", venvDir.toString());
      processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
      processBuilder.environment().put("PYTHONNOUSERSITE", "1");
      Process process = processBuilder.start();
      if (!process.waitFor(VENV_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new ProcessingException("Creating managed Python venv timed out at " + venvDir);
      }
      if (process.exitValue() != 0) {
        throw new ProcessingException("Creating managed Python venv failed at " + venvDir);
      }
      if (!Files.isExecutable(executable)) {
        @SuppressWarnings("java:S899")
        var _ = executable.toFile().setExecutable(true, true);
      }
      if (!Files.isExecutable(executable)) {
        throw new ProcessingException(
            "Managed Python venv executable was not created: " + executable);
      }
      markManagedVenvReady(venvDir);
    } catch (IOException e) {
      throw new ProcessingException("Could not create managed Python venv at " + venvDir, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException(
          "Interrupted while creating managed Python venv at " + venvDir, e);
    }
  }

  private Path installDir(Platform platform) {
    return cacheRoot
        .resolve(SystemParams.PYTHON)
        .resolve("standalone")
        .resolve(pythonVersion + "+" + pythonBuild)
        .resolve(platform.id());
  }

  private Path venvDir(Platform platform) {
    return cacheRoot
        .resolve(SystemParams.PYTHON)
        .resolve("venv")
        .resolve(pythonVersion + "+" + pythonBuild)
        .resolve(platform.id());
  }

  private Path standalonePythonExecutable(Platform platform, Path installDir) {
    String executable = platform.isWindows() ? "python.exe" : "bin/" + pythonBinaryName();
    return installDir.resolve(executable);
  }

  private Path venvPythonExecutable(Platform platform, Path venvDir) {
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
        "cpython " + pythonVersion + "+" + pythonBuild + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private void markManagedVenvReady(Path venvDir) throws IOException {
    Files.writeString(
        venvDir.resolve(VENV_READY_FILE),
        "cpython-venv " + pythonVersion + "+" + pythonBuild + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static Path lockKey(Path installDir) {
    return installDir.toAbsolutePath().normalize();
  }

  private byte[] download(URI uri) throws IOException {
    try {
      HttpRequest request = HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET().build();
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() / 100 != 2) {
        throw new ProcessingException("Download failed (" + response.statusCode() + "): " + uri);
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while downloading " + uri, e);
    }
  }

  private String downloadText(URI uri) throws IOException {
    return new String(download(uri), StandardCharsets.UTF_8);
  }

  private static void verifySha256(byte[] content, String archiveName, String shasums) {
    String expected =
        shasums
            .lines()
            .map(String::trim)
            .filter(line -> line.endsWith("  " + archiveName))
            .map(line -> line.substring(0, line.indexOf(' ')))
            .findFirst()
            .orElseThrow(
                () -> new ProcessingException("No CPython checksum found for " + archiveName));
    String actual = sha256(content);
    if (!expected.equalsIgnoreCase(actual)) {
      throw new ProcessingException(
          "CPython checksum mismatch for "
              + archiveName
              + ": expected "
              + expected
              + ", got "
              + actual);
    }
  }

  private static String sha256(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new ProcessingException("SHA-256 is not available", e);
    }
  }

  private static void extractTgz(byte[] archive, Path installDir) throws IOException {
    Path installRoot = installDir.toAbsolutePath().normalize();
    try (InputStream raw = new ByteArrayInputStream(archive);
        InputStream gzip = new GZIPInputStream(raw);
        TarArchiveInputStream in = new TarArchiveInputStream(gzip)) {
      TarArchiveEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (!entry.isDirectory() && !entry.isFile()) {
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
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
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
    return normalized.startsWith("v") ? normalized.substring(1) : normalized;
  }

  private static String normalizeBuild(String build) {
    return build == null || build.isBlank() ? DEFAULT_PYTHON_BUILD : build.trim();
  }

  private String pythonBinaryName() {
    int firstDot = pythonVersion.indexOf('.');
    int secondDot = pythonVersion.indexOf('.', firstDot + 1);
    if (firstDot < 0 || secondDot < 0) {
      return "python3";
    }
    return "python" + pythonVersion.substring(0, secondDot);
  }

  private static String encodeArchiveName(String archiveName) {
    return archiveName.replace("+", "%2B");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private record Platform(String os, String arch) {

    static Platform current() {
      String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
      String os =
          switch (platformOs(osName)) {
            case SystemParams.DARWIN -> SystemParams.APPLE_DARWIN;
            case SystemParams.LINUX -> SystemParams.UNKNOWN_LINUX_GNU;
            case "windows" -> "pc-windows-msvc";
            default -> throw new ProcessingException("Unsupported operating system: " + osName);
          };
      String arch =
          switch (archName) {
            case SystemParams.AARCH_64, SystemParams.ARM_64 -> SystemParams.AARCH_64;
            case SystemParams.AMD_64, SystemParams.X_86_64 -> SystemParams.X_86_64;
            default -> throw new ProcessingException("Unsupported CPU architecture: " + archName);
          };
      return new Platform(os, arch);
    }

    String id() {
      return arch + "-" + os;
    }

    boolean isWindows() {
      return os.equals("pc-windows-msvc");
    }

    String archiveName(String version, String build) {
      return "cpython-" + version + "+" + build + "-" + id() + "-install_only_stripped.tar.gz";
    }

    private static String platformOs(String osName) {
      if (osName.contains("mac") || osName.contains(SystemParams.DARWIN)) {
        return SystemParams.DARWIN;
      }
      if (osName.contains(SystemParams.LINUX)) {
        return SystemParams.LINUX;
      }
      if (osName.contains("win")) {
        return SystemParams.WINDOWS;
      }
      return osName;
    }
  }
}
