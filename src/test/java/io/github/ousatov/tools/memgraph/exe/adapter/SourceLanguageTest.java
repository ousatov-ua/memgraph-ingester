package io.github.ousatov.tools.memgraph.exe.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage.StaleModuleDefinitionCypher;
import java.util.Optional;
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

  @Test
  void filesLastModifiedCypherRoutesPerFirstClassLanguageAndDefaultsToCtags() {
    assertEquals(
        Cypher.CYPHER_GET_JAVA_FILES_LAST_MODIFIED, SourceLanguage.JAVA.filesLastModifiedCypher());
    assertEquals(
        Cypher.CYPHER_GET_JAVASCRIPT_FILES_LAST_MODIFIED,
        SourceLanguage.JAVASCRIPT.filesLastModifiedCypher());
    assertEquals(
        Cypher.CYPHER_GET_PYTHON_FILES_LAST_MODIFIED,
        SourceLanguage.PYTHON.filesLastModifiedCypher());
    assertEquals(
        Cypher.CYPHER_GET_CTAGS_FILES_LAST_MODIFIED,
        SourceLanguage.fromCtagsName("Ruby").filesLastModifiedCypher());
  }

  @Test
  void filesInSourceRootCypherRoutesPerFirstClassLanguageAndDefaultsToCtags() {
    assertEquals(
        Cypher.CYPHER_GET_JAVA_FILES_IN_SOURCE_ROOT, SourceLanguage.JAVA.filesInSourceRootCypher());
    assertEquals(
        Cypher.CYPHER_GET_JAVASCRIPT_FILES_IN_SOURCE_ROOT,
        SourceLanguage.JAVASCRIPT.filesInSourceRootCypher());
    assertEquals(
        Cypher.CYPHER_GET_PYTHON_FILES_IN_SOURCE_ROOT,
        SourceLanguage.PYTHON.filesInSourceRootCypher());
    assertEquals(
        Cypher.CYPHER_GET_CTAGS_FILES_IN_SOURCE_ROOT,
        SourceLanguage.fromCtagsName("Go").filesInSourceRootCypher());
  }

  @Test
  void sourceRootHintCypherRoutesPerFirstClassLanguageAndDefaultsToCtags() {
    assertEquals(
        Cypher.CYPHER_GET_JAVA_SOURCE_ROOT_HINT_FOR_FILE,
        SourceLanguage.JAVA.sourceRootHintCypher());
    assertEquals(
        Cypher.CYPHER_GET_JAVASCRIPT_SOURCE_ROOT_HINT_FOR_FILE,
        SourceLanguage.JAVASCRIPT.sourceRootHintCypher());
    assertEquals(
        Cypher.CYPHER_GET_PYTHON_SOURCE_ROOT_HINT_FOR_FILE,
        SourceLanguage.PYTHON.sourceRootHintCypher());
    assertEquals(
        Cypher.CYPHER_GET_CTAGS_SOURCE_ROOT_HINT_FOR_FILE,
        SourceLanguage.fromCtagsName("Rust").sourceRootHintCypher());
  }

  @Test
  void staleModuleDefinitionsCypherIsPresentOnlyForJavascriptAndPython() {
    Optional<StaleModuleDefinitionCypher> jsCypher =
        SourceLanguage.JAVASCRIPT.staleModuleDefinitionsCypher();
    assertTrue(jsCypher.isPresent());
    assertEquals(Cypher.CYPHER_DELETE_STALE_JAVASCRIPT_MEMBERS_FOR_FILE, jsCypher.get().members());
    assertEquals(Cypher.CYPHER_DELETE_STALE_JAVASCRIPT_OWNERS_FOR_FILE, jsCypher.get().owners());

    Optional<StaleModuleDefinitionCypher> pyCypher =
        SourceLanguage.PYTHON.staleModuleDefinitionsCypher();
    assertTrue(pyCypher.isPresent());
    assertEquals(Cypher.CYPHER_DELETE_STALE_PYTHON_MEMBERS_FOR_FILE, pyCypher.get().members());
    assertEquals(Cypher.CYPHER_DELETE_STALE_PYTHON_OWNERS_FOR_FILE, pyCypher.get().owners());

    assertFalse(SourceLanguage.JAVA.staleModuleDefinitionsCypher().isPresent());
    assertFalse(SourceLanguage.fromCtagsName("Ruby").staleModuleDefinitionsCypher().isPresent());
  }
}
