package io.github.ousatov.tools.memgraph.exe;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Downloads, verifies, caches, and exposes the Node.js executable owned by this ingester. */
public final class ManagedNodeRuntime {

  public static final String DEFAULT_NODE_VERSION = "22.11.0";

  private static final Logger log = LoggerFactory.getLogger(ManagedNodeRuntime.class);
  private static final String NODE_DIST = "https://nodejs.org/dist/";
  private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(5);
  private static final String LINUX = "linux";

  private final Path cacheRoot;
  private final String nodeVersion;
  private final RuntimeMode runtimeMode;
  private final HttpClient http;

  public ManagedNodeRuntime(Path cacheRoot, String nodeVersion, RuntimeMode runtimeMode) {
    this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
    this.nodeVersion = normalizeVersion(nodeVersion);
    this.runtimeMode = Objects.requireNonNull(runtimeMode, "runtimeMode");
    this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
  }

  public Path nodeExecutable() {
    if (runtimeMode == RuntimeMode.SYSTEM) {
      return systemNode();
    }
    Path executable = cachedNodeExecutable();
    if (Files.isExecutable(executable)) {
      return executable;
    }
    if (runtimeMode == RuntimeMode.OFFLINE) {
      throw new ProcessingException(
          "Managed Node.js "
              + nodeVersion
              + " is not cached at "
              + executable
              + "; disable --js-runtime-mode=offline or pre-warm the cache.");
    }
    installManagedNode(executable);
    return executable;
  }

  private Path systemNode() {
    String executable = isWindows() ? "node.exe" : "node";
    return Path.of(executable);
  }

  private void installManagedNode(Path executable) {
    Platform platform = Platform.current();
    Path installDir = installDir(platform);
    try {
      Files.createDirectories(installDir);
      if (Files.isExecutable(executable)) {
        return;
      }
      String archiveName = platform.archiveName(nodeVersion);
      URI archiveUri = URI.create(NODE_DIST + "v" + nodeVersion + "/" + archiveName);
      URI sumsUri = URI.create(NODE_DIST + "v" + nodeVersion + "/SHASUMS256.txt");
      log.atInfo()
          .setMessage("Checking managed Node.js {} ({}) availability")
          .addArgument(nodeVersion)
          .addArgument(platform::id)
          .log();
      byte[] archive = download(archiveUri);
      verifySha256(archive, archiveName, downloadText(sumsUri));
      extractNodeArchive(archiveName, archive, installDir);
      if (!Files.isExecutable(executable)) {
        @SuppressWarnings("java:S899")
        var _ = executable.toFile().setExecutable(true, true);
      }
      if (!Files.isExecutable(executable)) {
        throw new ProcessingException("Managed Node.js executable was not created: " + executable);
      }
    } catch (IOException e) {
      throw new ProcessingException("Could not install managed Node.js " + nodeVersion, e);
    }
  }

  private Path cachedNodeExecutable() {
    Platform platform = Platform.current();
    String executable = platform.isWindows() ? "node.exe" : "bin/node";
    return installDir(platform).resolve(executable);
  }

  private Path installDir(Platform platform) {
    return cacheRoot.resolve("node").resolve(nodeVersion).resolve(platform.id());
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
                () -> new ProcessingException("No Node.js checksum found for " + archiveName));
    String actual = sha256(content);
    if (!expected.equalsIgnoreCase(actual)) {
      throw new ProcessingException(
          "Node.js checksum mismatch for "
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

  private static void extractNodeArchive(String archiveName, byte[] archive, Path installDir)
      throws IOException {
    if (archiveName.endsWith(".zip")) {
      try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
        ZipEntry entry;
        while ((entry = in.getNextEntry()) != null) {
          extractEntry(installDir, entry.getName(), entry.isDirectory(), in);
        }
      }
      return;
    }
    try (InputStream raw = new ByteArrayInputStream(archive);
        InputStream compressed = compressedInput(archiveName, raw);
        TarArchiveInputStream in = new TarArchiveInputStream(compressed)) {
      TarArchiveEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (!entry.isDirectory() && !entry.isFile()) {
          continue;
        }
        extractEntry(installDir, entry.getName(), entry.isDirectory(), in);
      }
    }
  }

  private static InputStream compressedInput(String archiveName, InputStream raw)
      throws IOException {
    return archiveName.endsWith(".tar.xz")
        ? new XZCompressorInputStream(raw)
        : new GZIPInputStream(raw);
  }

  private static void extractEntry(
      Path installDir, String rawName, boolean directory, InputStream in) throws IOException {
    Path relative = stripTopDirectory(rawName);
    if (relative == null) {
      return;
    }
    Path installRoot = installDir.toAbsolutePath().normalize();
    Path target = installRoot.resolve(relative).normalize();
    if (!target.startsWith(installRoot)) {
      throw new ProcessingException("Archive entry escapes install directory: " + rawName);
    }
    if (directory) {
      Files.createDirectories(target);
      return;
    }
    Files.createDirectories(target.getParent());
    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private static Path stripTopDirectory(String rawName) {
    String normalized = rawName.replace('\\', '/');
    int slash = normalized.indexOf('/');
    if (slash < 0 || slash == normalized.length() - 1) {
      return null;
    }
    return Path.of(normalized.substring(slash + 1));
  }

  public static Path defaultCacheRoot() {
    String home = System.getProperty("user.home");
    if (home == null || home.isBlank()) {
      return Path.of(".memgraph-ingester-cache");
    }
    return Path.of(home, ".cache", "memgraph-ingester");
  }

  private static String normalizeVersion(String version) {
    String normalized =
        version == null || version.isBlank() ? DEFAULT_NODE_VERSION : version.trim();
    return normalized.startsWith("v") ? normalized.substring(1) : normalized;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private record Platform(String os, String arch) {

    static Platform current() {
      String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
      String os = osName.contains("win") ? "win" : getNix(osName);
      String arch =
          switch (archName) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64" -> "x64";
            default -> throw new ProcessingException("Unsupported CPU architecture: " + archName);
          };
      if (os.equals(LINUX) && !arch.equals("x64")) {
        throw new ProcessingException("Managed Node.js currently supports linux-x64 only");
      }
      if (os.equals("win") && !arch.equals("x64")) {
        throw new ProcessingException("Managed Node.js currently supports win-x64 only");
      }
      return new Platform(os, arch);
    }

    String id() {
      return os + "-" + arch;
    }

    boolean isWindows() {
      return os.equals("win");
    }

    String archiveName(String version) {
      String suffix = os.equals("win") ? ".zip" : getExt();
      return "node-v" + version + "-" + id() + suffix;
    }

    private @NonNull String getExt() {
      return os.equals(LINUX) ? ".tar.xz" : ".tar.gz";
    }
  }

  private static @NonNull String getNix(String osName) {
    return osName.contains("mac") || osName.contains("darwin") ? "darwin" : LINUX;
  }
}
