package io.github.ousatov.tools.memgraph.exe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for JavaScript and TypeScript source discovery.
 *
 * @author Oleksii Usatov
 */
class JsLanguageAdapterTest {

  private final JsLanguageAdapter adapter = new JsLanguageAdapter(null);

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
  void returnsFalseWhenRuntimeFailureEscapesAnalyzer() {
    assertFalse(adapter.ingestFile(null, Path.of("src/broken.js")));
  }
}
