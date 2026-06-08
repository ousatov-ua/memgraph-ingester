package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.config.AppConfig;
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads, verifies, caches, and exposes the Node.js executable owned by this ingester.
 *
 * @author Oleksii Usatov
 */
public final class ManagedNodeRuntime extends ManagedHttpInstaller {

  public static final String DEFAULT_NODE_VERSION =
      AppConfig.stringValue("runtime.managed.node.version");

  private static final Logger log = LoggerFactory.getLogger(ManagedNodeRuntime.class);
  private static final String NODE_DIST = AppConfig.stringValue("runtime.managed.node.dist-url");
  private static final String INSTALL_LOCK_FILE = Const.Files.INSTALL_LOCK;
  private static final String INSTALL_READY_FILE = Const.Files.INSTALL_COMPLETE;
  private static final ConcurrentMap<Path, Object> INSTALL_LOCKS = new ConcurrentHashMap<>();

  private final Path cacheRoot;
  private final String nodeVersion;
  private final RuntimeMode runtimeMode;

  public ManagedNodeRuntime(Path cacheRoot, String nodeVersion, RuntimeMode runtimeMode) {
    this.cacheRoot = Objects.requireNonNull(cacheRoot, Const.Params.CACHE_ROOT);
    this.nodeVersion = normalizeVersion(nodeVersion);
    this.runtimeMode = Objects.requireNonNull(runtimeMode, Const.Params.RUNTIME_MODE);
  }

  public Path nodeExecutable() {
    if (runtimeMode == RuntimeMode.SYSTEM) {
      return systemNode();
    }
    ManagedRuntimePlatform platform = ManagedRuntimePlatform.current();
    Path installDir = installDir(platform);
    Path executable = cachedNodeExecutable(platform, installDir);
    if (isManagedNodeReady(executable, installDir)) {
      return executable;
    }
    ensureManagedNodeInstalled(platform, installDir, executable);
    return executable;
  }

  private Path systemNode() {
    return Path.of(
        ManagedRuntimePlatform.isCurrentWindows() ? Const.Files.NODE_EXE : Const.SystemParams.NODE);
  }

  private void ensureManagedNodeInstalled(
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
          if (isManagedNodeReady(executable, installDir)) {
            return;
          }
          if (runtimeMode == RuntimeMode.OFFLINE && Files.isExecutable(executable)) {
            markManagedNodeReady(installDir);
            return;
          }
          if (runtimeMode == RuntimeMode.OFFLINE) {
            throw new ProcessingException(
                "Managed Node.js "
                    + nodeVersion
                    + " is not cached at "
                    + executable
                    + "; disable --js-runtime-mode=offline or pre-warm the cache.");
          }
          installManagedNode(platform, installDir, executable);
        }
      }
    } catch (IOException e) {
      throw new ProcessingException("Could not install managed Node.js " + nodeVersion, e);
    }
  }

  @SuppressWarnings("java:S106")
  private void installManagedNode(ManagedRuntimePlatform platform, Path installDir, Path executable)
      throws IOException {
    String archiveName = nodeArchiveName(platform, nodeVersion);
    URI archiveUri =
        URI.create(
            NODE_DIST
                + Const.SystemParams.VERSION_PREFIX
                + nodeVersion
                + Const.Symbols.SLASH
                + archiveName);
    URI sumsUri =
        URI.create(NODE_DIST + Const.SystemParams.VERSION_PREFIX + nodeVersion + "/SHASUMS256.txt");
    ConsoleStatusLine.withFinishedLine(
        System.err,
        () ->
            log.atInfo()
                .setMessage("Checking managed Node.js {} ({}) availability")
                .addArgument(nodeVersion)
                .addArgument(() -> nodeId(platform))
                .log());
    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.start("Node.js " + nodeVersion)) {
      byte[] archive = download(archiveUri);
      verifySha256(archive, archiveName, downloadText(sumsUri), "Node.js");
      extractNodeArchive(archiveName, archive, installDir);
      if (!Files.isExecutable(executable)) {
        boolean executableSet = executable.toFile().setExecutable(true, true);
        if (!executableSet && !Files.isExecutable(executable)) {
          throw new ProcessingException("Could not mark managed Node.js executable: " + executable);
        }
      }
      if (!Files.isExecutable(executable)) {
        throw new ProcessingException("Managed Node.js executable was not created: " + executable);
      }
      markManagedNodeReady(installDir);
      indicator.succeeded();
    }
  }

  private Path cachedNodeExecutable(ManagedRuntimePlatform platform, Path installDir) {
    return installDir.resolve(platform.executableName("bin/node", Const.Files.NODE_EXE));
  }

  private Path installDir(ManagedRuntimePlatform platform) {
    return cacheRoot
        .resolve(Const.SystemParams.NODE)
        .resolve(nodeVersion)
        .resolve(nodeId(platform));
  }

  private boolean isManagedNodeReady(Path executable, Path installDir) {
    return Files.isExecutable(executable)
        && Files.isRegularFile(installDir.resolve(INSTALL_READY_FILE));
  }

  private void markManagedNodeReady(Path installDir) throws IOException {
    Files.writeString(
        installDir.resolve(INSTALL_READY_FILE),
        "node " + nodeVersion + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static void extractNodeArchive(String archiveName, byte[] archive, Path installDir)
      throws IOException {
    if (archiveName.endsWith(Const.Files.ZIP)) {
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
    return archiveName.endsWith(Const.Files.TAR_XZ)
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
    return Path.of(home, ".cache", Const.SystemParams.MEMGRAPH_INGESTER);
  }

  private static String normalizeVersion(String version) {
    String normalized =
        version == null || version.isBlank() ? DEFAULT_NODE_VERSION : version.trim();
    return normalized.startsWith(Const.SystemParams.VERSION_PREFIX)
        ? normalized.substring(1)
        : normalized;
  }

  private static String nodeArchiveName(ManagedRuntimePlatform platform, String version) {
    return "node-v" + version + Const.Symbols.DASH + nodeId(platform) + nodeArchiveSuffix(platform);
  }

  private static String nodeId(ManagedRuntimePlatform platform) {
    return nodeOs(platform) + Const.Symbols.DASH + nodeArch(platform);
  }

  private static String nodeOs(ManagedRuntimePlatform platform) {
    if (platform.isWindows()) {
      return Const.SystemParams.WINDOWS_PREFIX;
    }
    if (platform.isMacos()) {
      return Const.SystemParams.DARWIN;
    }
    return "linux";
  }

  private static String nodeArch(ManagedRuntimePlatform platform) {
    return switch (platform.arch()) {
      case SystemParams.ARM_64 -> SystemParams.ARM_64;
      case SystemParams.X_86_64 -> SystemParams.X_64;
      default -> throw new ProcessingException("Unsupported CPU architecture: " + platform.arch());
    };
  }

  private static @NonNull String nodeArchiveSuffix(ManagedRuntimePlatform platform) {
    return platform.isWindows() ? Const.Files.ZIP : getExtension(platform);
  }

  private static @NonNull String getExtension(ManagedRuntimePlatform platform) {
    return platform.isLinux() ? Const.Files.TAR_XZ : Const.Files.TAR_GZ;
  }
}
