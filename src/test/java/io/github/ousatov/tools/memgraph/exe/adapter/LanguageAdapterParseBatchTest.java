package io.github.ousatov.tools.memgraph.exe.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for default {@link LanguageAdapter#parseBatch(List)} behavior.
 *
 * @author Oleksii Usatov
 */
class LanguageAdapterParseBatchTest {

  @Test
  void defaultParseBatchPreservesPerFileResults() {
    TestAdapter adapter = new TestAdapter();

    List<LanguageAdapter.ParseResult<String>> results =
        adapter.parseBatch(
            List.of(Path.of("ok.java"), Path.of("empty.java"), Path.of("fail.java")));

    assertEquals(3, results.size());
    assertEquals(Optional.of("ok.java"), results.get(0).parsed());
    assertTrue(results.get(1).parsed().isEmpty());
    assertNotNull(results.get(2).failure());
  }

  private static final class TestAdapter implements LanguageAdapter<String> {

    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVA;
    }

    @Override
    public boolean accepts(Path file) {
      return true;
    }

    @Override
    public Optional<String> parse(Path file) {
      String name = file.getFileName().toString();
      if ("fail.java".equals(name)) {
        throw new IllegalStateException("broken");
      }
      return "empty.java".equals(name) ? Optional.empty() : Optional.of(name);
    }

    @Override
    public SourceFileDefinitions collectDefinitions(String parsed) {
      return SourceFileDefinitions.empty();
    }

    @Override
    public boolean write(GraphWriter writer, Path file, String parsed) {
      return true;
    }
  }
}
