package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Downloads, verifies, and caches the pinned TypeScript compiler package used by the JS helper. */
public final class ManagedTypescriptPackage extends ManagedHttpInstaller {

  public static final String DEFAULT_TYPESCRIPT_VERSION =
      AppConfig.stringValue("runtime.managed.typescript.version");

  private static final Logger log = LoggerFactory.getLogger(ManagedTypescriptPackage.class);
  private static final String TYPESCRIPT_REGISTRY =
      AppConfig.stringValue("runtime.managed.typescript.registry-url");
  private static final Pattern TARBALL_PATTERN =
      Pattern.compile("\"tarball\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern INTEGRITY_PATTERN =
      Pattern.compile("\"integrity\"\\s*:\\s*\"sha512-([^\"]+)\"");
  public static final String PACKAGE = "package/";
  private static final String INSTALL_LOCK_FILE = Const.Files.INSTALL_LOCK;
  private static final String INSTALL_READY_FILE = Const.Files.INSTALL_COMPLETE;
  private static final String TYPESCRIPT_COMPILER = "lib/typescript.js";
  private static final ConcurrentMap<Path, Object> INSTALL_LOCKS = new ConcurrentHashMap<>();

  private final Path cacheRoot;
  private final String version;
  private final RuntimeMode runtimeMode;

  public ManagedTypescriptPackage(Path cacheRoot, String version, RuntimeMode runtimeMode) {
    this.cacheRoot = Objects.requireNonNull(cacheRoot, Const.Params.CACHE_ROOT);
    this.version = normalizeVersion(version);
    this.runtimeMode = Objects.requireNonNull(runtimeMode, Const.Params.RUNTIME_MODE);
  }

  public Path nodeModulesDir() {
    Path nodeModules = cacheRoot.resolve(Const.Files.NODE_MODULES).resolve("typescript-" + version);
    Path typescriptDir = nodeModules.resolve(Const.SystemParams.TYPESCRIPT);
    if (isTypescriptReady(typescriptDir)) {
      return nodeModules;
    }
    ensureTypescriptInstalled(typescriptDir);
    return nodeModules;
  }

  private void ensureTypescriptInstalled(Path typescriptDir) {
    try {
      Files.createDirectories(typescriptDir);
      Object localLock = INSTALL_LOCKS.computeIfAbsent(lockKey(typescriptDir), _ -> new Object());
      synchronized (localLock) {
        try (FileChannel channel =
                FileChannel.open(
                    typescriptDir.resolve(INSTALL_LOCK_FILE),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            var _ = channel.lock()) {
          if (isTypescriptReady(typescriptDir)) {
            return;
          }
          if (runtimeMode == RuntimeMode.OFFLINE
              && Files.isRegularFile(typescriptDir.resolve(TYPESCRIPT_COMPILER))) {
            markTypescriptReady(typescriptDir);
            return;
          }
          if (runtimeMode == RuntimeMode.OFFLINE) {
            throw new ProcessingException(
                "TypeScript "
                    + version
                    + " is not cached at "
                    + typescriptDir
                    + "; disable --js-runtime-mode=offline or pre-warm the cache.");
          }
          install(typescriptDir);
        }
      }
    } catch (IOException e) {
      throw new ProcessingException("Could not install TypeScript " + version, e);
    }
  }

  private boolean isTypescriptReady(Path typescriptDir) {
    return Files.isRegularFile(typescriptDir.resolve(TYPESCRIPT_COMPILER))
        && Files.isRegularFile(typescriptDir.resolve(INSTALL_READY_FILE));
  }

  private void markTypescriptReady(Path typescriptDir) throws IOException {
    Files.writeString(
        typescriptDir.resolve(INSTALL_READY_FILE),
        "typescript " + version + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static String normalizeVersion(String version) {
    String normalized =
        version == null || version.isBlank() ? DEFAULT_TYPESCRIPT_VERSION : version.trim();
    return normalized.startsWith(Const.SystemParams.VERSION_PREFIX)
        ? normalized.substring(1)
        : normalized;
  }

  private void install(Path typescriptDir) throws IOException {
    String metadata = downloadText(URI.create(TYPESCRIPT_REGISTRY + version));
    String tarball = extract(metadata, TARBALL_PATTERN, "TypeScript tarball URL");
    String integrity = extract(metadata, INTEGRITY_PATTERN, "TypeScript sha512 integrity");
    ConsoleStatusLine.withFinishedLine(
        System.err, () -> log.info("Downloading TypeScript compiler {}", version));
    byte[] archive = download(URI.create(tarball));
    verifySha512(archive, integrity);
    extractTgz(archive, typescriptDir);
    if (!Files.isRegularFile(typescriptDir.resolve(TYPESCRIPT_COMPILER))) {
      throw new ProcessingException("TypeScript compiler was not created: " + typescriptDir);
    }
    markTypescriptReady(typescriptDir);
  }

  private static String extract(String input, Pattern pattern, String description) {
    var matcher = pattern.matcher(input);
    if (!matcher.find()) {
      throw new ProcessingException("Could not find " + description);
    }
    return matcher.group(1);
  }

  private static void verifySha512(byte[] content, String expectedBase64) {
    try {
      byte[] expected = Base64.getDecoder().decode(expectedBase64);
      byte[] actual = MessageDigest.getInstance("SHA-512").digest(content);
      if (!MessageDigest.isEqual(expected, actual)) {
        throw new ProcessingException("TypeScript package checksum mismatch");
      }
    } catch (NoSuchAlgorithmException e) {
      throw new ProcessingException("SHA-512 is not available", e);
    }
  }

  @SuppressWarnings(Const.Warnings.LOOP_CONTROL)
  private static void extractTgz(byte[] archive, Path targetDir) throws IOException {
    Path targetRoot = targetDir.toAbsolutePath().normalize();
    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(archive));
        var tar = new TarArchiveInputStream(gzip)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (!entry.isDirectory() && !entry.isFile()) {
          continue;
        }
        Path relative = stripPackagePrefix(entry.getName());
        if (relative == null) {
          continue;
        }
        Path target = targetRoot.resolve(relative).normalize();
        if (!target.startsWith(targetRoot)) {
          throw new ProcessingException("Archive entry escapes TypeScript cache: " + entry);
        }
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static Path stripPackagePrefix(String rawName) {
    String normalized = rawName.replace('\\', '/');
    if (!normalized.startsWith(PACKAGE) || normalized.length() == PACKAGE.length()) {
      return null;
    }
    return Path.of(normalized.substring(PACKAGE.length()));
  }
}
