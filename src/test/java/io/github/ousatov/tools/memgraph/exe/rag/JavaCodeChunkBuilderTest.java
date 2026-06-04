package io.github.ousatov.tools.memgraph.exe.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.StaticJavaParser;
import io.github.ousatov.tools.memgraph.vo.writer.CodeChunkWrite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link JavaCodeChunkBuilder}. */
class JavaCodeChunkBuilderTest {

  @TempDir private Path tempDir;

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

    List<CodeChunkWrite> chunks =
        new JavaCodeChunkBuilder().build(file, StaticJavaParser.parse(source));

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

    List<CodeChunkWrite> chunks =
        new JavaCodeChunkBuilder().build(file, StaticJavaParser.parse(source));

    assertFalse(
        chunks.stream().anyMatch(chunk -> "com.example.Widget.<init>()".equals(chunk.sourceId())));
  }
}
