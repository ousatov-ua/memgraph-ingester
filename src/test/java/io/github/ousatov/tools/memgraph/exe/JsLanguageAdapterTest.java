package io.github.ousatov.tools.memgraph.exe;

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
  void rejectsTypeScriptDeclarationFiles() {
    for (String extension : List.of(".d.ts", ".d.mts", ".d.cts")) {
      assertFalse(adapter.accepts(Path.of("src/index" + extension)));
    }
  }

  @Test
  void rejectsNodeModulesPaths() {
    assertFalse(adapter.accepts(Path.of("node_modules/pkg/index.js")));
    assertFalse(adapter.accepts(Path.of("src/node_modules/pkg/index.ts")));
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
  void returnsFalseWhenRuntimeFailureEscapesAnalyzer() {
    assertFalse(adapter.ingestFile(null, Path.of("src/broken.js")));
  }
}
