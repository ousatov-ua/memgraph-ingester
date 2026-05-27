package io.github.ousatov.tools.memgraph.exe.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for static and ctags-detected source-language identities.
 *
 * @author Oleksii Usatov
 */
class SourceLanguageTest {

  @Test
  void returnsStaticLanguageForKnownGraphNames() {
    assertSame(SourceLanguage.JAVA, SourceLanguage.of("java", "Java"));
    assertSame(SourceLanguage.JAVASCRIPT, SourceLanguage.fromCtagsName("TypeScript"));
    assertSame(SourceLanguage.PYTHON, SourceLanguage.of("python", "Python"));
  }

  @Test
  void normalizesCtagsLanguageNames() {
    SourceLanguage cpp = SourceLanguage.fromCtagsName("C++");
    SourceLanguage csharp = SourceLanguage.fromCtagsName("C#");
    SourceLanguage objectiveC = SourceLanguage.fromCtagsName("Objective C");

    assertEquals("cpp", cpp.graphName());
    assertEquals("C++", cpp.nodeName());
    assertEquals("csharp", csharp.graphName());
    assertEquals("objective_c", objectiveC.graphName());
  }

  @Test
  void keepsDynamicLanguagesOutOfFirstClassSet() {
    SourceLanguage ruby = SourceLanguage.fromCtagsName("Ruby");

    assertEquals("ruby", ruby.graphName());
    assertEquals("Ruby", ruby.nodeName());
    assertTrue(SourceLanguage.supported().stream().noneMatch(ruby::equals));
  }
}
