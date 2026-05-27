package io.github.ousatov.tools.memgraph.exe.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalysis;
import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedCtagsRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.RuntimeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for ctags fallback adapter source selection.
 *
 * @author Oleksii Usatov
 */
class CtagsLanguageAdapterTest {

  @TempDir private Path tempDir;

  @Test
  void acceptsDetectedNonFirstClassLanguageOnly() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    CtagsLanguageAdapter adapter = adapterWithFakeCtags(sourceRoot, "Ruby");

    assertTrue(adapter.accepts(sourceRoot.resolve("service.rb")));
    assertFalse(adapter.accepts(sourceRoot.resolve("Service.java")));
    assertFalse(adapter.accepts(sourceRoot.resolve("app.py")));
  }

  @Test
  void rejectsFirstClassLanguagesEvenWhenExtensionIsUnknown() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");

    assertFalse(adapterWithFakeCtags(sourceRoot, "Java").accepts(sourceRoot.resolve("service")));
    assertFalse(
        adapterWithFakeCtags(sourceRoot, "JavaScript").accepts(sourceRoot.resolve("service")));
    assertFalse(adapterWithFakeCtags(sourceRoot, "Python").accepts(sourceRoot.resolve("service")));
  }

  @Test
  void rejectsNonProgrammingFilesByExtensionAndDetectedLanguage() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");

    assertFalse(adapterWithFakeCtags(sourceRoot, "Ruby").accepts(sourceRoot.resolve("config.yml")));
    assertFalse(adapterWithFakeCtags(sourceRoot, "Ruby").accepts(sourceRoot.resolve("pom.xml")));
    assertFalse(adapterWithFakeCtags(sourceRoot, "Ruby").accepts(sourceRoot.resolve("logo.svg")));
    assertFalse(
        adapterWithFakeCtags(sourceRoot, "Ruby").accepts(sourceRoot.resolve("app.properties")));
    assertFalse(
        adapterWithFakeCtags(sourceRoot, "YAML").accepts(sourceRoot.resolve("config.custom")));
    assertFalse(
        adapterWithFakeCtags(sourceRoot, "XML")
            .parse(sourceRoot.resolve("pom.custom"))
            .isPresent());
  }

  @Test
  void discoversDetectedFilesAndBuildsLanguagePrefixedPackage() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    CtagsLanguageAdapter adapter = adapterWithFakeCtags(sourceRoot, "Ruby");
    Path rubyFile = sourceRoot.resolve("app/models/service.rb");
    Path javaFile = sourceRoot.resolve("app/models/Service.java");
    Files.createDirectories(rubyFile.getParent());
    Files.writeString(rubyFile, "class Service\n  def call\n  end\nend\n");
    Files.writeString(javaFile, "class Service {}\n");

    assertIterableEquals(List.of(rubyFile), adapter.discoverFiles(sourceRoot));
    CtagsAnalysis analysis = adapter.parse(rubyFile).orElseThrow();

    assertEquals("ruby", analysis.language().graphName());
    assertEquals("ruby.app.models", analysis.packageName());
    assertEquals("ruby.app.models.service$2erb", analysis.moduleFqn());
    assertFalse(adapter.collectDefinitions(analysis).classFqns().isEmpty());
  }

  @Test
  void parsesFilesDiscoveredFromRelativeSourceRoot() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = Path.of("ctags-relative-source-" + System.nanoTime());
    Files.createDirectories(sourceRoot);
    try {
      CtagsLanguageAdapter adapter = adapterWithFakeCtags(sourceRoot, "Ruby");
      Path rubyFile = sourceRoot.resolve("service.rb");
      Files.writeString(rubyFile, "class Service\n  def call\n  end\nend\n");

      assertFalse(sourceRoot.isAbsolute());
      assertIterableEquals(List.of(rubyFile), adapter.discoverFiles(sourceRoot));
      assertTrue(adapter.parse(rubyFile).isPresent());
    } finally {
      deleteRecursively(sourceRoot);
    }
  }

  private CtagsLanguageAdapter adapterWithFakeCtags(Path sourceRoot, String language)
      throws IOException {
    Path cacheRoot = tempDir.resolve("runtime");
    Path executable = cachedExecutable(cacheRoot, "test");
    Files.createDirectories(executable.getParent());
    Files.writeString(
        executable,
        """
        #!/bin/sh
        last=""
        for arg in "$@"; do
          last="$arg"
          if [ "$arg" = "--print-language" ]; then
            print_language=1
          fi
        done
        if [ "$print_language" = "1" ]; then
            echo "$last: %s"
            exit 0
        fi
        if [ ! -f "$last" ]; then
            echo "missing file: $last" >&2
            exit 1
        fi
        cat <<'JSON'
        {"_type":"tag","name":"Service","path":"service.rb","line":1,"end":4,"kind":"class"}
        {"_type":"tag","name":"call","path":"service.rb","line":2,"end":3,"kind":"method","scope":"Service","signature":"()"}
        JSON
        """
            .formatted(language));
    executable.toFile().setExecutable(true, true);
    ManagedCtagsRuntime runtime = new ManagedCtagsRuntime(cacheRoot, "test", RuntimeMode.OFFLINE);
    return new CtagsLanguageAdapter(sourceRoot, new CtagsAnalyzer(sourceRoot, runtime));
  }

  private static Path cachedExecutable(Path cacheRoot, String version) {
    return cacheRoot
        .resolve("ctags")
        .resolve(version)
        .resolve(platformId())
        .resolve("bin")
        .resolve("ctags");
  }

  private static String platformId() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    String os = osName.contains("mac") || osName.contains("darwin") ? "macos" : "linux";
    String arch =
        switch (archName) {
          case "aarch64", "arm64" -> "arm64";
          default -> "x86_64";
        };
    return os + "-" + arch;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
