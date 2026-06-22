package io.github.ousatov.tools.memgraph.exe.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for JavaScript and TypeScript source discovery.
 *
 * @author Oleksii Usatov
 */
class JsLanguageAdapterTest {

  private final JsLanguageAdapter adapter = new JsLanguageAdapter(null);

  @TempDir private Path tempDir;

  @Test
  void acceptsModernNodeTypeScriptModuleExtensions() {
    for (String extension : List.of(".mts", ".cts")) {
      assertTrue(adapter.accepts(Path.of("src/index" + extension)));
    }
  }

  @Test
  void acceptsTypeScriptDeclarationFiles() {
    for (String extension : List.of(".d.ts", ".d.mts", ".d.cts")) {
      assertTrue(adapter.accepts(Path.of("src/index" + extension)));
    }
  }

  @Test
  void rejectsNodeModulesPaths() {
    assertFalse(adapter.accepts(Path.of("node_modules/pkg/index.js")));
    assertFalse(adapter.accepts(Path.of("src/node_modules/pkg/index.ts")));
  }

  @Test
  void rejectsCommonGeneratedAndRepositoryDirectories() {
    assertFalse(adapter.accepts(Path.of(".git/hooks/index.js")));
    assertFalse(adapter.accepts(Path.of("build/app.js")));
    assertFalse(adapter.accepts(Path.of("dist/app.js")));
    assertFalse(adapter.accepts(Path.of("out/app.js")));
  }

  @Test
  void discoverFilesSkipsNodeModulesSubtree() throws IOException {
    Path appFile = tempDir.resolve("src/app.ts");
    Path dependencyFile = tempDir.resolve("node_modules/pkg/index.ts");
    Files.createDirectories(appFile.getParent());
    Files.createDirectories(dependencyFile.getParent());
    Files.writeString(appFile, "export const app = 1;");
    Files.writeString(dependencyFile, "export const dependency = 1;");

    assertIterableEquals(List.of(appFile), adapter.discoverFiles(tempDir));
  }

  @Test
  void discoverFilesSkipsTargetSubtree() throws IOException {
    Path appFile = tempDir.resolve("src/app.ts");
    Path targetFile = tempDir.resolve("target/classes/app.js");
    Files.createDirectories(appFile.getParent());
    Files.createDirectories(targetFile.getParent());
    Files.writeString(appFile, "export const app = 1;");
    Files.writeString(targetFile, "export const generated = 1;");

    assertIterableEquals(List.of(appFile), adapter.discoverFiles(tempDir));
  }

  @Test
  void discoverFilesSkipsCommonGeneratedSubtrees() throws IOException {
    Path appFile = tempDir.resolve("src/app.ts");
    Path buildFile = tempDir.resolve("build/app.js");
    Path gitFile = tempDir.resolve(".git/hooks/index.js");
    Files.createDirectories(appFile.getParent());
    Files.createDirectories(buildFile.getParent());
    Files.createDirectories(gitFile.getParent());
    Files.writeString(appFile, "export const app = 1;");
    Files.writeString(buildFile, "export const generated = 1;");
    Files.writeString(gitFile, "export const hook = 1;");

    assertIterableEquals(List.of(appFile), adapter.discoverFiles(tempDir));
  }

  @Test
  void discoverFilesIgnoresNodeModulesOutsideSourceRoot() throws IOException {
    Path sourceRoot = tempDir.resolve("node_modules/project");
    Path appFile = sourceRoot.resolve("src/app.ts");
    Path dependencyFile = sourceRoot.resolve("src/node_modules/pkg/index.ts");
    Files.createDirectories(appFile.getParent());
    Files.createDirectories(dependencyFile.getParent());
    Files.writeString(appFile, "export const app = 1;");
    Files.writeString(dependencyFile, "export const dependency = 1;");

    assertIterableEquals(List.of(appFile), adapter.discoverFiles(sourceRoot));
  }

  @Test
  void returnsEmptyWhenRuntimeFailureEscapesAnalyzer() {
    assertTrue(adapter.parse(Path.of("src/broken.js")).isEmpty());
  }
}
