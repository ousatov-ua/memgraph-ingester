package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.SystemParams;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.vo.analysis.ctags.Release;
import io.github.ousatov.tools.memgraph.vo.analysis.ctags.ReleaseAsset;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the Universal Ctags executable used by fallback source-language ingestion.
 *
 * @author Oleksii Usatov
 */
public final class ManagedCtagsRuntime {

  private static final Logger log = LoggerFactory.getLogger(ManagedCtagsRuntime.class);

  public static final String DEFAULT_CTAGS_VERSION = "latest";
  private static final String GITHUB_BASE_URL = "https://github.com";
  private static final String CTAGS_ENV = "MEMGRAPH_INGESTER_CTAGS";
  private static final String INSTALL_LOCK_FILE = Const.Files.INSTALL_LOCK;
  private static final String INSTALL_READY_FILE = Const.Files.INSTALL_COMPLETE;
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern RELEASE_TAG_PATTERN =
      Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern ASSET_PATTERN =
      Pattern.compile(
          "\"url\"\\s*:\\s*\"[^\"]*/releases/assets/\\d+\".*?\"name\"\\s*:\\s*\"([^\"]+)\""
              + ".*?\"digest\"\\s*:\\s*(null|\"([^\"]+)\")"
              + ".*?\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"",
          Pattern.DOTALL);
  private static final Pattern RELEASE_ASSET_HTML_PATTERN =
      Pattern.compile(
          "<a\\b[^>]*\\bhref=\"([^\"]*/releases/download/[^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
  private static final Pattern RELEASE_ASSET_NAME_HTML_PATTERN =
      Pattern.compile(
          "<span(?=[^>]*class=\"[^\"]*\\bTruncate-text\\b)"
              + "(?=[^>]*class=\"[^\"]*\\btext-bold\\b)[^>]*>([^<]+)</span>",
          Pattern.DOTALL);
  private static final Pattern RELEASE_ASSET_DIGEST_HTML_PATTERN =
      Pattern.compile(
          "<span(?=[^>]*class=\"[^\"]*\\bTruncate-text\\b)[^>]*>" + "(sha256:[a-fA-F0-9]+)</span>",
          Pattern.DOTALL);
  private static final ConcurrentMap<String, Object> INSTALL_LOCKS = new ConcurrentHashMap<>();
  public static final String CTAGS_EXE = "ctags.exe";
  public static final String RELEASE = ".release";
  public static final String CTAGS = Const.SystemParams.CTAGS;
  public static final String MEMGRAPH_INGESTER = Const.SystemParams.MEMGRAPH_INGESTER;
  public static final String USER_AGENT = "User-Agent";

  private final Path cacheRoot;
  private final String ctagsVersion;
  private final RuntimeMode runtimeMode;
  private final HttpClient http;

  public ManagedCtagsRuntime(Path cacheRoot, String ctagsVersion, RuntimeMode runtimeMode) {
    this.cacheRoot = Objects.requireNonNull(cacheRoot, Const.Params.CACHE_ROOT);
    this.ctagsVersion = normalizeVersion(ctagsVersion);
    this.runtimeMode = Objects.requireNonNull(runtimeMode, Const.Params.RUNTIME_MODE);
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  /** Returns the ctags executable path or command name. */
  public Path ctagsExecutable() {
    return switch (runtimeMode) {
      case SYSTEM -> systemCtags();
      case OFFLINE -> offlineCtags();
      case MANAGED -> managedCtags();
    };
  }

  /** Returns the shared parser-runtime cache root. */
  public static Path defaultCacheRoot() {
    return ManagedNodeRuntime.defaultCacheRoot();
  }

  private Path systemCtags() {
    String configured = System.getenv(CTAGS_ENV);
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured.trim());
    }
    return Path.of(ManagedRuntimePlatform.isCurrentWindows() ? CTAGS_EXE : CTAGS);
  }

  private Path offlineCtags() {
    ManagedRuntimePlatform platform = ManagedRuntimePlatform.current();
    if (DEFAULT_CTAGS_VERSION.equals(ctagsVersion)) {
      return cachedLatestCtags(platform)
          .orElseThrow(
              () ->
                  new ProcessingException(
                      "Universal Ctags "
                          + ctagsVersion
                          + " is not cached under "
                          + cacheRoot.resolve(CTAGS)
                          + " for "
                          + ctagsId(platform)
                          + "; use --ctags-runtime-mode=managed or pre-warm the cache."));
    }
    Path executable = cachedExecutable(ctagsVersion, platform);
    if (Files.isExecutable(executable)) {
      return executable;
    }
    throw new ProcessingException(
        "Universal Ctags "
            + ctagsVersion
            + " is not cached at "
            + executable
            + "; use --ctags-runtime-mode=system or pre-warm the cache.");
  }

  private Path managedCtags() {
    ManagedRuntimePlatform platform = ManagedRuntimePlatform.current();
    Optional<Path> cached = cachedManagedCtags(platform);
    if (cached.isPresent()) {
      return cached.get();
    }
    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.start(
            "Universal Ctags "
                + ctagsVersion
                + Const.Symbols.SPACE_LEFT_PAREN
                + ctagsId(platform)
                + Const.Symbols.RIGHT_PAREN)) {
      ReleaseAsset asset = releaseAsset(platform);
      Path installDir = installDir(asset.tag(), platform);
      Path executable = cachedExecutable(asset.tag(), platform);
      if (Files.isExecutable(executable) && Files.exists(installDir.resolve(INSTALL_READY_FILE))) {
        indicator.succeeded();
        return executable;
      }
      ensureManagedCtagsInstalled(platform, asset, installDir, executable);
      indicator.succeeded();
      return executable;
    }
  }

  private Optional<Path> cachedManagedCtags(ManagedRuntimePlatform platform) {
    if (DEFAULT_CTAGS_VERSION.equals(ctagsVersion)) {
      return cachedLatestCtags(platform);
    }
    Path installDir = installDir(ctagsVersion, platform);
    Path executable = cachedExecutable(ctagsVersion, platform);
    return isManagedCtagsReady(executable, installDir) ? Optional.of(executable) : Optional.empty();
  }

  private Optional<Path> cachedLatestCtags(ManagedRuntimePlatform platform) {
    Path ctagsRoot = cacheRoot.resolve(CTAGS);
    if (!Files.isDirectory(ctagsRoot)) {
      return Optional.empty();
    }
    try (var tagDirs = Files.list(ctagsRoot)) {
      return tagDirs
          .filter(Files::isDirectory)
          .sorted(Comparator.reverseOrder())
          .map(tagDir -> cachedCtagsInTagDir(tagDir, platform))
          .flatMap(Optional::stream)
          .findFirst();
    } catch (IOException _) {
      return Optional.empty();
    }
  }

  private Optional<Path> cachedCtagsInTagDir(Path tagDir, ManagedRuntimePlatform platform) {
    Path installDir = tagDir.resolve(ctagsId(platform));
    Path executable =
        installDir.resolve(Const.Files.BIN).resolve(platform.executableName(CTAGS, CTAGS_EXE));
    return isManagedCtagsReady(executable, installDir) ? Optional.of(executable) : Optional.empty();
  }

  private static boolean isManagedCtagsReady(Path executable, Path installDir) {
    return Files.isExecutable(executable) && Files.exists(installDir.resolve(INSTALL_READY_FILE));
  }

  private void ensureManagedCtagsInstalled(
      ManagedRuntimePlatform platform, ReleaseAsset asset, Path installDir, Path executable) {
    try {
      Files.createDirectories(installDir);
      Object localLock = INSTALL_LOCKS.computeIfAbsent(installDir.toString(), _ -> new Object());
      synchronized (localLock) {
        try (FileChannel channel =
                FileChannel.open(
                    installDir.resolve(INSTALL_LOCK_FILE),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            var _ = channel.lock()) {
          if (isManagedCtagsReady(executable, installDir)) {
            return;
          }
          byte[] archive = download(asset);
          verifyDigest(asset, archive);
          String executableName = platform.executableName(CTAGS, CTAGS_EXE);
          extractArchive(asset.name(), archive, installDir, executableName);
          Path extracted = findCtagsExecutable(installDir, executableName);
          Files.createDirectories(executable.getParent());
          if (!extracted.equals(executable)) {
            Files.copy(extracted, executable, StandardCopyOption.REPLACE_EXISTING);
          }
          executable.toFile().setExecutable(true, true);
          Files.writeString(
              installDir.resolve(INSTALL_READY_FILE),
              asset.name()
                  + Const.Symbols.NEW_LINE
                  + asset.digest().orElse(Const.Symbols.EMPTY)
                  + Const.Symbols.NEW_LINE,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING);
        }
      }
    } catch (IOException e) {
      throw new ProcessingException("Could not install managed Universal Ctags", e);
    }
  }

  private ReleaseAsset releaseAsset(ManagedRuntimePlatform platform) {
    Release release = fetchRelease(platform);
    return release.assets().stream()
        .filter(asset -> ctagsAssetMatches(platform, asset.name()))
        .filter(asset -> asset.name().endsWith(ctagsArchiveSuffix(platform)))
        .filter(asset -> !asset.name().contains(".debug."))
        .findFirst()
        .orElseThrow(
            () ->
                new ProcessingException(
                    "No managed Universal Ctags asset matching "
                        + ctagsAssetDescription(platform)
                        + " in release "
                        + release.tag()));
  }

  private Release fetchRelease(ManagedRuntimePlatform platform) {
    String repository =
        platform.isWindows()
            ? "universal-ctags/ctags-win32"
            : "universal-ctags/ctags-nightly-build";
    if (DEFAULT_CTAGS_VERSION.equals(ctagsVersion)) {
      return fetchLatestReleaseFromGithubPage(repository);
    }
    try {
      return fetchReleaseFromApi(repository);
    } catch (ProcessingException apiFailure) {
      log.debug(
          "Falling back to GitHub releases page for Universal Ctags {} metadata: {}",
          ctagsVersion,
          apiFailure.getMessage());
      try {
        return fetchReleaseAssetsFromGithubPage(repository, ctagsVersion);
      } catch (ProcessingException pageFailure) {
        pageFailure.addSuppressed(apiFailure);
        throw pageFailure;
      }
    }
  }

  private Release fetchReleaseFromApi(String repository) {
    String encodedVersion = URLEncoder.encode(ctagsVersion, StandardCharsets.UTF_8);
    String endpoint =
        "https://api.github.com/repos/" + repository + "/releases/tags/" + encodedVersion;
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(HTTP_TIMEOUT)
            .header(USER_AGENT, MEMGRAPH_INGESTER)
            .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new ProcessingException(
            "Could not fetch Universal Ctags release metadata from "
                + endpoint
                + ": HTTP "
                + response.statusCode());
      }
      return parseRelease(response.body());
    } catch (IOException e) {
      throw new ProcessingException("Could not fetch Universal Ctags release metadata", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while fetching Universal Ctags metadata", e);
    }
  }

  private Release fetchLatestReleaseFromGithubPage(String repository) {
    URI latestUri =
        URI.create(GITHUB_BASE_URL + Const.Symbols.SLASH + repository + "/releases/latest");
    HttpRequest request =
        HttpRequest.newBuilder(latestUri)
            .timeout(HTTP_TIMEOUT)
            .header(USER_AGENT, MEMGRAPH_INGESTER)
            .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new ProcessingException(
            "Could not fetch Universal Ctags latest release page from "
                + latestUri
                + ": HTTP "
                + response.statusCode());
      }
      String tag =
          releaseTagFromUri(response.uri())
              .orElseThrow(
                  () ->
                      new ProcessingException(
                          "Universal Ctags latest release page did not redirect to a tag"));
      return fetchReleaseAssetsFromGithubPage(repository, tag);
    } catch (IOException e) {
      throw new ProcessingException("Could not fetch Universal Ctags latest release page", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while fetching Universal Ctags latest release", e);
    }
  }

  private Release fetchReleaseAssetsFromGithubPage(String repository, String tag) {
    String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8);
    URI assetsUri =
        URI.create(
            GITHUB_BASE_URL
                + Const.Symbols.SLASH
                + repository
                + "/releases/expanded_assets/"
                + encodedTag);
    HttpRequest request =
        HttpRequest.newBuilder(assetsUri)
            .timeout(HTTP_TIMEOUT)
            .header(USER_AGENT, MEMGRAPH_INGESTER)
            .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new ProcessingException(
            "Could not fetch Universal Ctags release assets from "
                + assetsUri
                + ": HTTP "
                + response.statusCode());
      }
      return parseReleaseAssetsPage(tag, response.body());
    } catch (IOException e) {
      throw new ProcessingException("Could not fetch Universal Ctags release assets", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while fetching Universal Ctags release assets", e);
    }
  }

  private static Optional<String> releaseTagFromUri(URI uri) {
    String path = uri.getPath();
    String marker = "/releases/tag/";
    int index = path.indexOf(marker);
    if (index < 0) {
      return Optional.empty();
    }
    return Optional.of(path.substring(index + marker.length()));
  }

  private static Release parseRelease(String json) {
    Matcher tagMatcher = RELEASE_TAG_PATTERN.matcher(json);
    if (!tagMatcher.find()) {
      throw new ProcessingException("Universal Ctags release metadata did not include tag_name");
    }
    String tag = unescapeJson(tagMatcher.group(1));
    Matcher assetMatcher = ASSET_PATTERN.matcher(json);
    java.util.List<ReleaseAsset> assets = new java.util.ArrayList<>();
    while (assetMatcher.find()) {
      Optional<String> digest =
          Const.SystemParams.NULL.equals(assetMatcher.group(2))
              ? Optional.empty()
              : Optional.of(assetMatcher.group(3));
      assets.add(
          new ReleaseAsset(
              tag,
              unescapeJson(assetMatcher.group(1)),
              digest,
              unescapeJson(assetMatcher.group(4))));
    }
    return new Release(tag, java.util.List.copyOf(assets));
  }

  static Release parseReleaseAssetsPage(String tag, String html) {
    Matcher assetMatcher = RELEASE_ASSET_HTML_PATTERN.matcher(html);
    java.util.List<ReleaseAsset> assets = new java.util.ArrayList<>();
    while (assetMatcher.find()) {
      String href = unescapeHtml(assetMatcher.group(1));
      String assetHtml = assetMatcher.group(2);
      Matcher nameMatcher = RELEASE_ASSET_NAME_HTML_PATTERN.matcher(assetHtml);
      if (!nameMatcher.find()) {
        continue;
      }
      Optional<String> digest = releaseAssetDigest(html, assetMatcher.end(), assetHtml);
      String name = unescapeHtml(nameMatcher.group(1));
      String url = href.startsWith("http") ? href : GITHUB_BASE_URL + href;
      assets.add(new ReleaseAsset(tag, name, digest, url));
    }
    if (assets.isEmpty()) {
      throw new ProcessingException("Universal Ctags release assets page did not list downloads");
    }
    return new Release(tag, java.util.List.copyOf(assets));
  }

  private static Optional<String> releaseAssetDigest(String html, int assetEnd, String assetHtml) {
    Optional<String> digest = releaseAssetDigest(assetHtml);
    if (digest.isPresent()) {
      return digest;
    }
    int rowEnd = html.indexOf("</li>", assetEnd);
    if (rowEnd < 0) {
      return Optional.empty();
    }
    return releaseAssetDigest(html.substring(assetEnd, rowEnd));
  }

  private static Optional<String> releaseAssetDigest(String html) {
    Matcher digestMatcher = RELEASE_ASSET_DIGEST_HTML_PATTERN.matcher(html);
    return digestMatcher.find()
        ? Optional.of(unescapeHtml(digestMatcher.group(1)))
        : Optional.empty();
  }

  private byte[] download(ReleaseAsset asset) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(asset.url()))
            .timeout(HTTP_TIMEOUT)
            .header(USER_AGENT, MEMGRAPH_INGESTER)
            .build();
    try {
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() >= 400) {
        throw new ProcessingException(
            "Could not download Universal Ctags asset "
                + asset.name()
                + ": HTTP "
                + response.statusCode());
      }
      return response.body();
    } catch (IOException e) {
      throw new ProcessingException("Could not download Universal Ctags asset " + asset.name(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while downloading Universal Ctags", e);
    }
  }

  private static void verifyDigest(ReleaseAsset asset, byte[] archive) {
    Optional<String> expected =
        asset
            .digest()
            .filter(digest -> digest.startsWith(Const.SystemParams.SHA_256_PREFIX))
            .map(digest -> digest.substring(Const.SystemParams.SHA_256_PREFIX.length()));
    if (expected.isEmpty()) {
      log.warn(
          "Skipping checksum verification for Universal Ctags asset {} because the release did"
              + " not publish a SHA-256 digest",
          asset.name());
      return;
    }
    String actual = sha256(archive);
    if (!expected.get().equalsIgnoreCase(actual)) {
      throw new ProcessingException(
          "Universal Ctags checksum mismatch for "
              + asset.name()
              + ": expected "
              + expected.get()
              + " but got "
              + actual);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance(Const.SystemParams.SHA_256).digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new ProcessingException("SHA-256 is not available", e);
    }
  }

  private static void extractArchive(
      String name, byte[] archive, Path installDir, String executableName) throws IOException {
    if (name.endsWith(Const.Files.ZIP)) {
      extractZip(archive, installDir, executableName);
    } else if (name.endsWith(Const.Files.TAR_XZ)) {
      extractTar(
          new XZCompressorInputStream(new ByteArrayInputStream(archive)),
          installDir,
          executableName);
    } else if (name.endsWith(Const.Files.TAR_GZ)) {
      extractTar(
          new java.util.zip.GZIPInputStream(new ByteArrayInputStream(archive)),
          installDir,
          executableName);
    } else {
      throw new ProcessingException("Unsupported Universal Ctags archive: " + name);
    }
  }

  private static void extractTar(InputStream input, Path installDir, String executableName)
      throws IOException {
    try (TarArchiveInputStream in = new TarArchiveInputStream(input)) {
      TarArchiveEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        extractEntry(installDir, entry.getName(), entry.isDirectory(), in, executableName);
      }
    }
  }

  private static void extractZip(byte[] archive, Path installDir, String executableName)
      throws IOException {
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        extractEntry(installDir, entry.getName(), entry.isDirectory(), in, executableName);
      }
    }
  }

  private static void extractEntry(
      Path installDir, String rawName, boolean directory, InputStream in, String executableName)
      throws IOException {
    Path target = installDir.toAbsolutePath().normalize().resolve(rawName).normalize();
    if (!target.startsWith(installDir.toAbsolutePath().normalize())) {
      throw new ProcessingException("Archive entry escapes install directory: " + rawName);
    }
    if (directory) {
      Files.createDirectories(target);
      return;
    }
    Files.createDirectories(target.getParent());
    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    if (executableName.equals(target.getFileName().toString())) {
      target.toFile().setExecutable(true, true);
    }
  }

  private static Path findCtagsExecutable(Path installDir, String executableName) {
    try (var paths = Files.walk(installDir)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> executableName.equals(path.getFileName().toString()))
          .findFirst()
          .orElseThrow(
              () ->
                  new ProcessingException(
                      "Managed Universal Ctags archive did not contain " + executableName));
    } catch (IOException e) {
      throw new ProcessingException("Could not inspect managed Universal Ctags install", e);
    }
  }

  private Path installDir(String tag, ManagedRuntimePlatform platform) {
    return cacheRoot.resolve(CTAGS).resolve(sanitizePathSegment(tag)).resolve(ctagsId(platform));
  }

  private Path cachedExecutable(String tag, ManagedRuntimePlatform platform) {
    return installDir(tag, platform)
        .resolve(Const.Files.BIN)
        .resolve(platform.executableName(CTAGS, CTAGS_EXE));
  }

  private static String ctagsId(ManagedRuntimePlatform platform) {
    return platform.os() + Const.Symbols.DASH + platform.arch();
  }

  private static String ctagsAssetDescription(ManagedRuntimePlatform platform) {
    if (platform.isWindows()) {
      return platform.arch().equals(SystemParams.X86) ? "-x86.zip" : "-x64.zip";
    }
    return platform.os() + Const.Symbols.DASH + ctagsReleaseArch(platform) + RELEASE;
  }

  private static boolean ctagsAssetMatches(ManagedRuntimePlatform platform, String name) {
    if (platform.isWindows()) {
      return name.contains(ctagsAssetDescription(platform));
    }
    if (platform.isMacos()) {
      return name.contains("macos-")
          && name.contains(Const.Symbols.DASH + ctagsReleaseArch(platform) + RELEASE);
    }
    return name.contains(platform.os() + Const.Symbols.DASH + ctagsReleaseArch(platform) + RELEASE);
  }

  private static String ctagsReleaseArch(ManagedRuntimePlatform platform) {
    if (platform.isMacos()) {
      return platform.arch();
    }
    return platform.arch().equals(SystemParams.ARM_64) ? SystemParams.AARCH_64 : platform.arch();
  }

  private static String ctagsArchiveSuffix(ManagedRuntimePlatform platform) {
    return platform.isWindows() ? Const.Files.ZIP : Const.Files.TAR_XZ;
  }

  private static String normalizeVersion(String version) {
    return version == null || version.isBlank() ? DEFAULT_CTAGS_VERSION : version.trim();
  }

  private static String sanitizePathSegment(String value) {
    return value.replace('/', '_').replace('\\', '_').replace(':', '_');
  }

  private static String unescapeJson(String value) {
    return value
        .replace("\\/", Const.Symbols.SLASH)
        .replace("\\\"", Const.Symbols.DOUBLE_QUOTE)
        .replace("\\\\", "\\");
  }

  private static String unescapeHtml(String value) {
    return value.replace("&amp;", "&").replace("&quot;", Const.Symbols.DOUBLE_QUOTE);
  }
}
