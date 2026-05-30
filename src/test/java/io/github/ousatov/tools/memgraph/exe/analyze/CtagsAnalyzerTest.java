package io.github.ousatov.tools.memgraph.exe.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ctags language and JSON Lines parsing.
 *
 * @author Oleksii Usatov
 */
class CtagsAnalyzerTest {

  @Test
  void parsesDetectedLanguageFromCtagsOutput() {
    SourceLanguage language =
        CtagsAnalyzer.parseDetectedLanguage("/repo/app/models/user.rb: Ruby").orElseThrow();

    assertEquals("ruby", language.graphName());
    assertEquals("Ruby", language.nodeName());
  }

  @Test
  void ignoresUnknownDetectedLanguage() {
    assertFalse(CtagsAnalyzer.parseDetectedLanguage("/repo/README: NONE").isPresent());
  }

  @Test
  void parsesCtagsJsonTagLinesAndSkipsMalformedRows() {
    List<io.github.ousatov.tools.memgraph.vo.analysis.ctags.CtagsTag> tags =
        CtagsAnalyzer.parseTags(
            """
            {"_type":"tag","name":"Service","path":"service.rb","line":1,"kind":"class"}
            {"_type":"tag","name":"call","path":"service.rb","line":2,"end":4,"kind":"method","scope":"Service","signature":"(value)","typeref":"Integer","access":"public"}
            {"_type":"ptag","name":"ignored"}
            not-json
            """);

    assertEquals(2, tags.size());
    assertEquals("Service", tags.getFirst().name());
    assertEquals("call", tags.get(1).name());
    assertEquals("Service", tags.get(1).scope());
    assertEquals(4, tags.get(1).endLine());
  }
}
