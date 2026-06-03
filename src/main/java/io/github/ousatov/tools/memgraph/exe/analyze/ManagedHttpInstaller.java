package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** Common HTTP download and checksum behavior for managed tool installers. */
abstract class ManagedHttpInstaller {

  private static final Duration HTTP_TIMEOUT =
      AppConfig.durationValue("runtime.managed.http-timeout");

  private final HttpClient http;

  protected ManagedHttpInstaller() {
    this(false);
  }

  protected ManagedHttpInstaller(boolean followRedirects) {
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT);
    if (followRedirects) {
      builder.followRedirects(HttpClient.Redirect.NORMAL);
    }
    this.http = builder.build();
  }

  protected byte[] download(URI uri) throws IOException {
    try {
      HttpRequest request = HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET().build();
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() / 100 != 2) {
        throw new ProcessingException(
            "Download failed ("
                + response.statusCode()
                + Const.Symbols.RIGHT_PAREN_COLON_SPACE
                + uri);
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while downloading " + uri, e);
    }
  }

  protected String downloadText(URI uri) throws IOException {
    return new String(download(uri), StandardCharsets.UTF_8);
  }

  protected static Path lockKey(Path installDir) {
    return installDir.toAbsolutePath().normalize();
  }

  protected static void verifySha256(
      byte[] content, String archiveName, String shasums, String productName) {
    String expected =
        shasums
            .lines()
            .map(String::trim)
            .filter(line -> line.endsWith(Const.Symbols.TWO_SPACES + archiveName))
            .map(line -> line.substring(0, line.indexOf(' ')))
            .findFirst()
            .orElseThrow(
                () ->
                    new ProcessingException(
                        "No " + productName + " checksum found for " + archiveName));
    String actual = sha256(content);
    if (!expected.equalsIgnoreCase(actual)) {
      throw new ProcessingException(
          productName
              + " checksum mismatch for "
              + archiveName
              + ": expected "
              + expected
              + Const.Symbols.COMMA_GOT
              + actual);
    }
  }

  private static String sha256(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance(Const.SystemParams.SHA_256);
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new ProcessingException("SHA-256 is not available", e);
    }
  }
}
