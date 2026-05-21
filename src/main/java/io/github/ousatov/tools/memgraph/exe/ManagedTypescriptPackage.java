package io.github.ousatov.tools.memgraph.exe;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Downloads, verifies, and caches the pinned TypeScript compiler package used by the JS helper. */
public final class ManagedTypescriptPackage {

  public static final String DEFAULT_TYPESCRIPT_VERSION = "5.6.3";

  private static final Logger log = LoggerFactory.getLogger(ManagedTypescriptPackage.class);
  private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(5);
  private static final Pattern TARBALL_PATTERN =
      Pattern.compile("\"tarball\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern INTEGRITY_PATTERN =
      Pattern.compile("\"integrity\"\\s*:\\s*\"sha512-([^\"]+)\"");
  public static final String PACKAGE = "package/";

  private final Path cacheRoot;
  private final String version;
  private final RuntimeMode runtimeMode;
  private final HttpClient http;

  public ManagedTypescriptPackage(Path cacheRoot, String version, RuntimeMode runtimeMode) {
    this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
    this.version =
        version == null || version.isBlank() ? DEFAULT_TYPESCRIPT_VERSION : version.trim();
    this.runtimeMode = Objects.requireNonNull(runtimeMode, "runtimeMode");
    this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
  }

  public Path nodeModulesDir() {
    Path nodeModules = cacheRoot.resolve("node_modules").resolve("typescript-" + version);
    Path typescriptDir = nodeModules.resolve("typescript");
    if (Files.isRegularFile(typescriptDir.resolve("lib/typescript.js"))) {
      return nodeModules;
    }
    if (runtimeMode == RuntimeMode.OFFLINE) {
      throw new ProcessingException(
          "TypeScript "
              + version
              + " is not cached at "
              + typescriptDir
              + "; disable --js-runtime-mode=offline or pre-warm the cache.");
    }
    install(nodeModules, typescriptDir);
    return nodeModules;
  }

  private void install(
      @SuppressWarnings({"java:S1172", "unused"}) Path nodeModules, Path typescriptDir) {
    try {
      Files.createDirectories(typescriptDir);
      String metadata =
          downloadText(URI.create("https://registry.npmjs.org/typescript/" + version));
      String tarball = extract(metadata, TARBALL_PATTERN, "TypeScript tarball URL");
      String integrity = extract(metadata, INTEGRITY_PATTERN, "TypeScript sha512 integrity");
      log.info("Downloading TypeScript compiler {}", version);
      byte[] archive = download(URI.create(tarball));
      verifySha512(archive, integrity);
      extractTgz(archive, typescriptDir);
    } catch (IOException e) {
      throw new ProcessingException("Could not install TypeScript " + version, e);
    }
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

  @SuppressWarnings("java:S135")
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
