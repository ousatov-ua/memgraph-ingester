package io.github.ousatov.tools.memgraph.exe.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import io.github.ousatov.tools.memgraph.vo.writer.CodeChunkWrite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link JavaCodeChunkBuilder}.
 *
 * @author Oleksii Usatov
 */
class JavaCodeChunkBuilderTest {

  @TempDir private Path tempDir;

  private static CompilationUnit parseJava25(String source) {
    ParserConfiguration config = new ParserConfiguration();
    config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25);
    return new JavaParser(config).parse(source).getResult().orElseThrow();
  }

  @Test
  void emitsImplicitDefaultConstructorChunkForClassWithoutConstructor() throws IOException {
    String source =
        """
        package com.example;
        class Widget {
          private String name;
          void work() {}
        }
        """;
    Path file = tempDir.resolve("Widget.java");
    Files.writeString(file, source);

    List<CodeChunkWrite> chunks = new JavaCodeChunkBuilder().build(file, parseJava25(source));

    CodeChunkWrite ctor =
        chunks.stream()
            .filter(chunk -> "com.example.Widget.<init>()".equals(chunk.sourceId()))
            .findFirst()
            .orElseThrow();
    assertEquals("Method", ctor.sourceLabel());
    assertEquals("com.example.Widget", ctor.ownerFqn());
    assertEquals("com.example.Widget.<init>()", ctor.signature());
    assertEquals("synthetic", ctor.ragRole());
    assertTrue(ctor.synthetic());
    assertTrue(ctor.text().contains("Name: <init>"));

    CodeChunkWrite field =
        chunks.stream()
            .filter(chunk -> "com.example.Widget#name".equals(chunk.sourceId()))
            .findFirst()
            .orElseThrow();
    assertEquals("Field", field.sourceLabel());
    assertEquals("secondary", field.ragRole());
    assertFalse(field.synthetic());
  }

  @Test
  void methodChunkTextLeadsWithEmbeddingFriendlyHead() throws IOException {
    String source =
        """
        package com.example;
        class WidgetFactory {
          void doWork() {}
        }
        """;
    Path file = tempDir.resolve("WidgetFactory.java");
    Files.writeString(file, source);

    List<CodeChunkWrite> chunks = new JavaCodeChunkBuilder().build(file, parseJava25(source));

    CodeChunkWrite method =
        chunks.stream()
            .filter(chunk -> "com.example.WidgetFactory.doWork()".equals(chunk.sourceId()))
            .findFirst()
            .orElseThrow();
    assertTrue(
        method
            .text()
            .startsWith(
                """
                Name: doWork
                Kind: method
                Owner: WidgetFactory
                Words: do work widget factory
                Source excerpt:
                """));
    assertFalse(method.text().contains("Path: "));
    assertFalse(method.text().contains("Language: "));
    assertFalse(method.text().contains("com.example"));
  }

  @Test
  void fileChunkTextIncludesDefinesAndSplitWords() throws IOException {
    String source =
        """
        package com.example;
        class WidgetFactory {
          void doWork() {}
        }
        """;
    Path file = tempDir.resolve("WidgetFactory.java");
    Files.writeString(file, source);

    List<CodeChunkWrite> chunks = new JavaCodeChunkBuilder().build(file, parseJava25(source));

    CodeChunkWrite fileChunk =
        chunks.stream()
            .filter(chunk -> "File".equals(chunk.sourceLabel()))
            .findFirst()
            .orElseThrow();
    assertTrue(fileChunk.text().contains("Name: WidgetFactory.java"));
    assertTrue(fileChunk.text().contains("Kind: file"));
    assertTrue(fileChunk.text().contains("Words: widget factory java class methods do work"));
    assertTrue(fileChunk.text().contains("Defines: WidgetFactory (class); methods: doWork"));
    assertTrue(fileChunk.text().contains("Source excerpt:\npackage com.example;"));
    assertFalse(fileChunk.text().contains("Path: "));
  }

  @Test
  void doesNotEmitImplicitDefaultConstructorChunkForInterface() throws IOException {
    String source =
        """
        package com.example;
        interface Widget {
          void work();
        }
        """;
    Path file = tempDir.resolve("Widget.java");
    Files.writeString(file, source);

    List<CodeChunkWrite> chunks = new JavaCodeChunkBuilder().build(file, parseJava25(source));

    assertFalse(
        chunks.stream().anyMatch(chunk -> "com.example.Widget.<init>()".equals(chunk.sourceId())));
  }

  @Test
  void emitsSyntheticRecordConstructorAndAccessorChunks() throws IOException {
    String source =
        """
        package com.example;
        record Point(int x) {}
        """;
    Path file = tempDir.resolve("Point.java");
    Files.writeString(file, source);

    List<CodeChunkWrite> chunks = new JavaCodeChunkBuilder().build(file, parseJava25(source));

    CodeChunkWrite component =
        chunks.stream()
            .filter(chunk -> "com.example.Point#x".equals(chunk.sourceId()))
            .findFirst()
            .orElseThrow();
    assertEquals("Field", component.sourceLabel());
    assertEquals("secondary", component.ragRole());
    assertFalse(component.synthetic());

    CodeChunkWrite ctor =
        chunks.stream()
            .filter(chunk -> "com.example.Point.<init>(int)".equals(chunk.sourceId()))
            .findFirst()
            .orElseThrow();
    assertEquals("Method", ctor.sourceLabel());
    assertEquals("synthetic", ctor.ragRole());
    assertTrue(ctor.synthetic());
    assertTrue(ctor.text().contains("record Point(int x)"));

    CodeChunkWrite accessor =
        chunks.stream()
            .filter(chunk -> "com.example.Point.x()".equals(chunk.sourceId()))
            .findFirst()
            .orElseThrow();
    assertEquals("Method", accessor.sourceLabel());
    assertEquals("synthetic", accessor.ragRole());
    assertTrue(accessor.synthetic());
    assertTrue(accessor.text().contains("record Point(int x)"));
  }
}
