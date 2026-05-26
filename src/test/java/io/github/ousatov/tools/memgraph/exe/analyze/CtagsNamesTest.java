package io.github.ousatov.tools.memgraph.exe.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ctags fallback graph identity generation.
 *
 * @author Oleksii Usatov
 */
class CtagsNamesTest {

  @Test
  void packageNameIncludesDetectedLanguagePrefix() {
    SourceLanguage ruby = SourceLanguage.fromCtagsName("Ruby");
    Path root = Path.of("/repo");
    Path file = root.resolve("app/models/user.rb");

    assertEquals("ruby.app.models", CtagsNames.packageName(ruby, root, file));
  }

  @Test
  void moduleFqnIsLanguageAndPathStable() {
    SourceLanguage rust = SourceLanguage.fromCtagsName("Rust");
    Path root = Path.of("/repo");
    Path file = root.resolve("src/lib.rs");

    assertEquals("rust.src.lib$2ers", CtagsNames.moduleFqn(rust, root, file));
  }

  @Test
  void signaturesEncodeParameterText() {
    assertEquals(
        "ruby.app.Service.call(value, other)",
        CtagsNames.methodSignature("ruby.app.Service", "call", "(value, other)"));
  }
}
