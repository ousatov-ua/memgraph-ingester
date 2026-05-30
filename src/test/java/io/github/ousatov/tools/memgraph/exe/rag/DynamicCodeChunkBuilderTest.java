package io.github.ousatov.tools.memgraph.exe.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalysis;
import io.github.ousatov.tools.memgraph.exe.analyze.JsAnalysis;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalysis;
import io.github.ousatov.tools.memgraph.vo.writer.CodeChunkWrite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for non-Java {@link CommonCodeChunkBuilder} implementations.
 *
 * @author Oleksii Usatov
 */
class DynamicCodeChunkBuilderTest {

  @TempDir private Path tempDir;

  @Test
  void jsBuilderEmitsImplicitConstructorChunkForClassWithoutConstructor() throws IOException {
    Path file = sourceFile("widget.ts", "class Widget {\n  work() {}\n}\n");
    JsAnalysis analysis =
        new JsAnalysis(
            "js.widget",
            "widget.ts",
            "js",
            "widget.ts",
            1,
            3,
            List.of(
                new io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl(
                    Params.CLASS, "js.widget.Widget", "Widget", "", false, false, 1, 3)),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    CodeChunkWrite ctor =
        constructorChunk(new JsCodeChunkBuilder().build(file, analysis), "js.widget.Widget");

    assertEquals("js.widget.Widget.<init>()", ctor.sourceId());
    assertEquals("js.widget.Widget.<init>()", ctor.signature());
    assertTrue(ctor.text().contains("Name: <init>"));
  }

  @Test
  void jsBuilderSkipsImplicitConstructorChunkWhenConstructorIsDeclared() throws IOException {
    Path file = sourceFile("widget.ts", "class Widget {\n  constructor() {}\n}\n");
    JsAnalysis analysis =
        new JsAnalysis(
            "js.widget",
            "widget.ts",
            "js",
            "widget.ts",
            1,
            3,
            List.of(
                new io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl(
                    Params.CLASS, "js.widget.Widget", "Widget", "", true, false, 1, 3)),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    List<CodeChunkWrite> chunks = new JsCodeChunkBuilder().build(file, analysis);

    assertFalse(
        chunks.stream().anyMatch(chunk -> "js.widget.Widget.<init>()".equals(chunk.sourceId())));
  }

  @Test
  void pythonBuilderEmitsImplicitConstructorChunkForClassWithoutInit() throws IOException {
    Path file = sourceFile("widget.py", "class Widget:\n    pass\n");
    PythonAnalysis analysis =
        new PythonAnalysis(
            "python.widget",
            "widget.py",
            "python",
            "widget.py",
            1,
            2,
            List.of(
                new io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl(
                    Params.CLASS, "python.widget.Widget", "Widget", "", false, false, 1, 2)),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    CodeChunkWrite ctor =
        constructorChunk(
            new PythonCodeChunkBuilder().build(file, analysis), "python.widget.Widget");

    assertEquals("python.widget.Widget.<init>()", ctor.sourceId());
    assertEquals("python.widget.Widget.<init>()", ctor.signature());
  }

  @Test
  void ctagsBuilderEmitsSyntheticConstructorChunkForClass() throws IOException {
    Path file = sourceFile("widget.rb", "class Widget\nend\n");
    CtagsAnalysis analysis =
        new CtagsAnalysis(
            SourceLanguage.of("ruby", "Ruby"),
            "ruby.widget",
            "widget.rb",
            "ruby",
            "widget.rb",
            1,
            2,
            List.of(
                new io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl(
                    Params.CLASS, Params.CLASS, "ruby.widget.Widget", "Widget", false, 1, 2)),
            List.of());

    CodeChunkWrite ctor =
        constructorChunk(new CtagsCodeChunkBuilder().build(file, analysis), "ruby.widget.Widget");

    assertEquals("ruby.widget.Widget.<init>()", ctor.sourceId());
    assertEquals("ruby.widget.Widget.<init>()", ctor.signature());
  }

  private Path sourceFile(String name, String content) throws IOException {
    Path file = tempDir.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  private static CodeChunkWrite constructorChunk(List<CodeChunkWrite> chunks, String ownerFqn) {
    return chunks.stream()
        .filter(chunk -> (ownerFqn + ".<init>()").equals(chunk.sourceId()))
        .findFirst()
        .orElseThrow();
  }
}
