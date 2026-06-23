package io.github.ousatov.tools.memgraph.exe.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedCtagsRuntime;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.vo.analysis.CtagsAnalysis;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
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
    Files.createDirectories(sourceRoot);
    Files.writeString(sourceRoot.resolve("service.rb"), "class Service\nend\n");

    assertTrue(adapter.accepts(sourceRoot.resolve("service.rb")));
    assertFalse(adapter.accepts(sourceRoot.resolve("Service.java")));
    assertFalse(adapter.accepts(sourceRoot.resolve("app.py")));
  }

  @Test
  void rejectsFirstClassLanguagesEvenWhenExtensionIsUnknown() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    Files.createDirectories(sourceRoot);
    Files.writeString(sourceRoot.resolve("service"), "class Service\nend\n");

    assertFalse(adapterWithFakeCtags(sourceRoot, "Java").accepts(sourceRoot.resolve("service")));
    assertFalse(
        adapterWithFakeCtags(sourceRoot, "JavaScript").accepts(sourceRoot.resolve("service")));
    assertFalse(adapterWithFakeCtags(sourceRoot, "Python").accepts(sourceRoot.resolve("service")));
  }

  @Test
  void rejectsNonProgrammingFilesByExtensionAndDetectedLanguage() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    Files.createDirectories(sourceRoot);
    Files.writeString(sourceRoot.resolve("config.custom"), "name: value\n");
    Files.writeString(sourceRoot.resolve("pom.custom"), "<project />\n");

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
  void rejectsDocumentationAndStylesheetSources() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    Files.createDirectories(sourceRoot);
    Path adocFile = sourceRoot.resolve("README.adoc");
    Path scssFile = sourceRoot.resolve("style.scss");
    Files.writeString(adocFile, "= Title\n");
    Files.writeString(scssFile, "$color: red;\n");

    CtagsLanguageAdapter adapter = adapterWithFakeCtags(sourceRoot, "Ruby");

    assertFalse(adapter.accepts(adocFile));
    assertFalse(adapter.accepts(scssFile));
    assertIterableEquals(List.of(), adapter.discoverFiles(sourceRoot));

    Path extensionlessDoc = sourceRoot.resolve("README");
    Path extensionlessStyle = sourceRoot.resolve("style");
    Files.writeString(extensionlessDoc, "= Title\n");
    Files.writeString(extensionlessStyle, "$color: red;\n");

    assertFalse(adapterWithFakeCtags(sourceRoot, "Asciidoc").accepts(extensionlessDoc));
    assertFalse(adapterWithFakeCtags(sourceRoot, "SCSS").accepts(extensionlessStyle));
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
  void shallowlyDiscoversCypherFilesWithoutSymbolTags() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    CtagsLanguageAdapter adapter = adapterWithFakeCtags(sourceRoot, "Cypher", "");
    Path cypherFile =
        sourceRoot
            .resolve("src")
            .resolve("main")
            .resolve("resources")
            .resolve("queries")
            .resolve("upsert-file.cypher");
    Files.createDirectories(cypherFile.getParent());
    Files.writeString(
        cypherFile,
        """
        MATCH (p:Project {name: $project})
        MERGE (f:File {path: $path, project: $project})
        RETURN f
        """);

    assertIterableEquals(List.of(cypherFile), adapter.discoverFiles(sourceRoot));
    CtagsAnalysis analysis = adapter.parse(cypherFile).orElseThrow();

    assertEquals("cypher", analysis.language().graphName());
    assertEquals("Cypher", analysis.language().nodeName());
    assertEquals(List.of(), analysis.types());
    assertEquals(List.of(), analysis.members());
    SourceFileDefinitions definitions = adapter.collectDefinitions(analysis);
    assertTrue(definitions.classFqns().contains(analysis.moduleFqn()));
    assertTrue(definitions.methodSignatures().contains(analysis.moduleFqn() + ".<init>()"));
    assertTrue(definitions.interfaceFqns().isEmpty());
    assertTrue(definitions.fieldFqns().isEmpty());
  }

  @Test
  void reusesDetectedLanguageWhenParsingDiscoveredFile() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    Path counter = tempDir.resolve("print-language-count");
    CtagsLanguageAdapter adapter = adapterWithCountingFakeCtags(sourceRoot, "Ruby", counter);
    Path rubyFile = sourceRoot.resolve("service.rb");
    Files.createDirectories(sourceRoot);
    Files.writeString(rubyFile, "class Service\nend\n");

    assertIterableEquals(List.of(rubyFile), adapter.discoverFiles(sourceRoot));
    assertTrue(adapter.parse(rubyFile).isPresent());

    assertEquals("1", Files.readString(counter).strip());
  }

  @Test
  void discoversFilesWhenSourceRootParentHasSkippedDirectoryName() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("build").resolve("repo");
    CtagsLanguageAdapter adapter = adapterWithFakeCtags(sourceRoot, "Ruby");
    Path rubyFile = sourceRoot.resolve("service.rb");
    Files.createDirectories(sourceRoot);
    Files.writeString(rubyFile, "class Service\nend\n");

    assertIterableEquals(List.of(rubyFile), adapter.discoverFiles(sourceRoot));
  }

  @Test
  void discoverFilesSkipsTargetSubtree() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    CtagsLanguageAdapter adapter = adapterWithFakeCtags(sourceRoot, "Ruby");
    Path rubyFile = sourceRoot.resolve("app/service.rb");
    Path targetFile = sourceRoot.resolve("target/classes/service.rb");
    Files.createDirectories(rubyFile.getParent());
    Files.createDirectories(targetFile.getParent());
    Files.writeString(rubyFile, "class Service\nend\n");
    Files.writeString(targetFile, "class GeneratedService\nend\n");

    assertIterableEquals(List.of(rubyFile), adapter.discoverFiles(sourceRoot));
  }

  @Test
  void acceptsDeletedFallbackPathWithoutReadingMissingFile() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    CtagsLanguageAdapter adapter = adapterWithFakeCtags(sourceRoot, "Ruby");

    assertTrue(adapter.acceptsDeletedPath(sourceRoot.resolve("service.rb")));
    assertTrue(adapter.acceptsDeletedPath(Path.of("service.rb")));
    assertFalse(adapter.acceptsDeletedPath(sourceRoot.resolve("Service.java")));
    assertFalse(adapter.acceptsDeletedPath(sourceRoot.resolve("config.yml")));
    assertFalse(adapter.acceptsDeletedPath(sourceRoot.resolve("vendor/service.rb")));
  }

  @Test
  void treatsCtagsEnumsAsTypes() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    CtagsLanguageAdapter adapter =
        adapterWithFakeCtags(
            sourceRoot,
            "Rust",
            """
            {"_type":"tag","name":"Color","path":"color.rs","line":1,"end":4,"kind":"enum"}
            {"_type":"tag","name":"Red","path":"color.rs","line":2,"kind":"enumerator","scope":"Color"}
            """);
    Path enumFile = sourceRoot.resolve("color.rs");
    Files.createDirectories(sourceRoot);
    Files.writeString(enumFile, "enum Color { Red }\n");

    CtagsAnalysis analysis = adapter.parse(enumFile).orElseThrow();

    assertEquals(
        List.of(Params.ENUM),
        analysis.types().stream()
            .map(io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl::graphKind)
            .toList());
    assertFalse(
        analysis.members().stream()
            .anyMatch(
                member ->
                    "Color".equals(member.name()) && Params.FIELD.equals(member.memberType())));
    assertTrue(
        analysis.members().stream()
            .anyMatch(
                member ->
                    "Red".equals(member.name())
                        && member.ownerFqn().endsWith(".Color")
                        && Labels.CLASS.equals(member.ownerKind())));
  }

  @Test
  void skipsUnsupportedCtagsKinds() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    CtagsLanguageAdapter adapter =
        adapterWithFakeCtags(
            sourceRoot,
            "Rust",
            """
            {"_type":"tag","name":"Service","path":"service.rs","line":1,"end":1,"kind":"struct"}
            {"_type":"tag","name":"Service","path":"service.rs","line":3,"end":5,"kind":"implementation"}
            {"_type":"tag","name":"call","path":"service.rs","line":4,"kind":"method","scope":"Service","signature":"()"}
            """);
    Path rustFile = sourceRoot.resolve("service.rs");
    Files.createDirectories(sourceRoot);
    Files.writeString(rustFile, "struct Service;\nimpl Service { fn call(&self) {} }\n");

    CtagsAnalysis analysis = adapter.parse(rustFile).orElseThrow();

    assertFalse(
        analysis.members().stream()
            .anyMatch(
                member ->
                    "Service".equals(member.name())
                        && member.fqnOrSignature().equals(analysis.types().getFirst().fqn())));
    assertTrue(analysis.members().stream().anyMatch(member -> "call".equals(member.name())));
  }

  @Test
  void preservesTagEndLineWhenSourceCannotBeDecodedAsUtf8() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    CtagsLanguageAdapter adapter = adapterWithFakeCtags(sourceRoot, "Ruby");
    Path rubyFile = sourceRoot.resolve("service.rb");
    Files.createDirectories(sourceRoot);
    Files.write(rubyFile, new byte[] {(byte) 0xC3, 0x28});

    CtagsAnalysis analysis = adapter.parse(rubyFile).orElseThrow();

    assertEquals(4, analysis.endLine());
  }

  @Test
  void resolvesOutOfOrderTypeScopesBeforeAssigningChildFqns() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    CtagsLanguageAdapter adapter =
        adapterWithFakeCtags(
            sourceRoot,
            "Go",
            """
            {"_type":"tag","name":"Service","path":"main.go","line":3,"end":5,"kind":"struct","scope":"main"}
            {"_type":"tag","name":"main","path":"main.go","line":1,"end":1,"kind":"package"}
            {"_type":"tag","name":"NewService","path":"main.go","line":7,"end":9,"kind":"function","scope":"main","signature":"()"}
            """);
    Path goFile = sourceRoot.resolve("main.go");
    Files.createDirectories(sourceRoot);
    Files.writeString(goFile, "package main\n");

    CtagsAnalysis analysis = adapter.parse(goFile).orElseThrow();
    io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl packageType =
        analysis.types().stream()
            .filter(type -> "main".equals(type.name()))
            .findFirst()
            .orElseThrow();
    io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl serviceType =
        analysis.types().stream()
            .filter(type -> "Service".equals(type.name()))
            .findFirst()
            .orElseThrow();

    assertEquals(packageType.fqn() + ".Service", serviceType.fqn());
    SourceFileDefinitions definitions = adapter.collectDefinitions(analysis);
    assertFalse(definitions.methodSignatures().contains(packageType.fqn() + ".<init>()"));
    assertFalse(definitions.methodSignatures().contains(serviceType.fqn() + ".<init>()"));
    assertTrue(
        analysis.members().stream()
            .anyMatch(
                member ->
                    "NewService".equals(member.name())
                        && packageType.fqn().equals(member.ownerFqn())
                        && Labels.CLASS.equals(member.ownerKind())));
  }

  @Test
  void redetectsLanguageAfterFileContentChanges() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = tempDir.resolve("repo");
    CtagsLanguageAdapter adapter = adapterWithContentSensitiveFakeCtags(sourceRoot);
    Path sourceFile = sourceRoot.resolve("service");
    Files.createDirectories(sourceRoot);
    Files.writeString(sourceFile, "class Service\nend\n");

    assertTrue(adapter.accepts(sourceFile));

    Files.writeString(sourceFile, "console.log('now first class');\n");

    assertFalse(adapter.accepts(sourceFile));
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

  @Test
  void parsesRootLocalPathStartingWithRelativeSourceRootName() throws IOException {
    assumeFalse(isWindows(), "fake executable script is POSIX-only");
    Path sourceRoot = Path.of("ctags-relative-source-" + System.nanoTime());
    Path rootLocalFile = sourceRoot.getFileName().resolve("service.rb");
    Path rubyFile = sourceRoot.resolve(rootLocalFile);
    Files.createDirectories(rubyFile.getParent());
    try {
      CtagsLanguageAdapter adapter = adapterWithFakeCtags(sourceRoot, "Ruby");
      Files.writeString(rubyFile, "class Service\n  def call\n  end\nend\n");

      assertFalse(sourceRoot.isAbsolute());
      assertTrue(adapter.parse(rootLocalFile).isPresent());
    } finally {
      deleteRecursively(sourceRoot);
    }
  }

  @Test
  void propagatesRuntimeFailuresDuringDiscovery() throws IOException {
    Path sourceRoot = tempDir.resolve("repo");
    Files.createDirectories(sourceRoot);
    Files.writeString(sourceRoot.resolve("service.rb"), "class Service\nend\n");
    ManagedCtagsRuntime runtime =
        new ManagedCtagsRuntime(tempDir.resolve("missing-runtime"), "missing", RuntimeMode.OFFLINE);
    CtagsLanguageAdapter adapter =
        new CtagsLanguageAdapter(sourceRoot, new CtagsAnalyzer(sourceRoot, runtime));

    assertThrows(ProcessingException.class, () -> adapter.discoverFiles(sourceRoot));
  }

  private CtagsLanguageAdapter adapterWithFakeCtags(Path sourceRoot, String language)
      throws IOException {
    return adapterWithFakeCtags(
        sourceRoot,
        language,
        """
        {"_type":"tag","name":"Service","path":"service.rb","line":1,"end":4,"kind":"class"}
        {"_type":"tag","name":"call","path":"service.rb","line":2,"end":3,"kind":"method","scope":"Service","signature":"()"}
        """);
  }

  private CtagsLanguageAdapter adapterWithFakeCtags(
      Path sourceRoot, String language, String tagsJsonLines) throws IOException {
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
          if [ "$arg" = "--fields=+nKlsSe" ]; then
            fields_seen=1
          fi
          if [ "$arg" = "--langdef=Cypher" ]; then
            cypher_langdef_seen=1
          fi
          if [ "$arg" = "--map-Cypher=+.cypher" ]; then
            cypher_map_seen=1
          fi
        done
        if [ "$cypher_langdef_seen" != "1" ] || [ "$cypher_map_seen" != "1" ]; then
            echo "missing built-in Cypher ctags options" >&2
            exit 2
        fi
        if [ "$print_language" = "1" ]; then
            echo "$last: %s"
            exit 0
        fi
        if [ "$fields_seen" != "1" ]; then
            echo "missing end-line field" >&2
            exit 2
        fi
        if [ ! -f "$last" ]; then
            echo "missing file: $last" >&2
            exit 1
        fi
        cat <<'JSON'
        %s
        JSON
        """
            .formatted(language, tagsJsonLines.stripTrailing()));
    executable.toFile().setExecutable(true, true);
    ManagedCtagsRuntime runtime = new ManagedCtagsRuntime(cacheRoot, "test", RuntimeMode.OFFLINE);
    return new CtagsLanguageAdapter(sourceRoot, new CtagsAnalyzer(sourceRoot, runtime));
  }

  private CtagsLanguageAdapter adapterWithContentSensitiveFakeCtags(Path sourceRoot)
      throws IOException {
    Path cacheRoot = tempDir.resolve("runtime");
    Path executable = cachedExecutable(cacheRoot, "content-sensitive");
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
            if grep -q "console.log" "$last"; then
                echo "$last: JavaScript"
            else
                echo "$last: Ruby"
            fi
            exit 0
        fi
        cat <<'JSON'
        {"_type":"tag","name":"Service","path":"service","line":1,"end":2,"kind":"class"}
        JSON
        """);
    executable.toFile().setExecutable(true, true);
    ManagedCtagsRuntime runtime =
        new ManagedCtagsRuntime(cacheRoot, "content-sensitive", RuntimeMode.OFFLINE);
    return new CtagsLanguageAdapter(sourceRoot, new CtagsAnalyzer(sourceRoot, runtime));
  }

  private CtagsLanguageAdapter adapterWithCountingFakeCtags(
      Path sourceRoot, String language, Path counter) throws IOException {
    Path cacheRoot = tempDir.resolve("runtime");
    Path executable = cachedExecutable(cacheRoot, "counting");
    Files.createDirectories(executable.getParent());
    Files.writeString(
        executable,
        """
        #!/bin/sh
        counter=%s
        last=""
        for arg in "$@"; do
          last="$arg"
          if [ "$arg" = "--print-language" ]; then
            print_language=1
          fi
          if [ "$arg" = "--fields=+nKlsSe" ]; then
            fields_seen=1
          fi
        done
        if [ "$print_language" = "1" ]; then
            count=0
            if [ -f "$counter" ]; then
              count=$(cat "$counter")
            fi
            count=$((count + 1))
            printf '%%s' "$count" > "$counter"
            echo "$last: %s"
            exit 0
        fi
        if [ "$fields_seen" != "1" ]; then
            echo "missing end-line field" >&2
            exit 2
        fi
        cat <<'JSON'
        {"_type":"tag","name":"Service","path":"service.rb","line":1,"end":2,"kind":"class"}
        JSON
        """
            .formatted(shellSingleQuoted(counter), language));
    executable.toFile().setExecutable(true, true);
    ManagedCtagsRuntime runtime =
        new ManagedCtagsRuntime(cacheRoot, "counting", RuntimeMode.OFFLINE);
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

  private static String shellSingleQuoted(Path path) {
    return "'" + path.toString().replace("'", "'\\''") + "'";
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
