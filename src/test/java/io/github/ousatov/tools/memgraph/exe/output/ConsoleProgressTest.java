package io.github.ousatov.tools.memgraph.exe.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Tests console progress rendering.
 *
 * @author Oleksii Usatov
 */
class ConsoleProgressTest {

  @Test
  void finiteRendererShowsAsciiBarAndPercent() {
    String rendered = ConsoleProgress.renderFinite("Installing packages", 5, 10, 0, false);

    assertTrue(rendered.contains("Installing packages"));
    assertTrue(rendered.contains("[==============>"));
    assertTrue(rendered.contains("50%"));
    assertTrue(rendered.endsWith("5/10"));
  }

  @Test
  void finiteRendererUsesNonRedAnsiProgressAccents() {
    String rendered = ConsoleProgress.renderFinite("Installing packages", 5, 10, 0, true);

    assertTrue(rendered.contains("\u001B[94m"));
    assertTrue(rendered.contains("\u001B[96m"));
    assertFalse(rendered.contains("\u001B[31m"));
    assertFalse(rendered.contains("\u001B[91m"));
  }

  @Test
  void indeterminateRendererBouncesBackFromRightEdge() {
    String forward = ConsoleProgress.renderIndeterminate("Resolving graph", 0, false);
    String backward = ConsoleProgress.renderIndeterminate("Resolving graph", 30, false);

    assertTrue(forward.contains("[==>"));
    assertTrue(backward.contains("<=="));
    assertNotEquals(forward, backward);
  }

  @Test
  void completedRendererUsesAlignedFullBar() {
    String ingested = ConsoleProgress.renderFinite("Ingested source files", 186, 186, 0, false);
    String runtime =
        ConsoleProgress.renderComplete("Loaded managed runtime: Node.js 22.11.0", false);

    assertAlignedProgressBar(ingested, runtime);
  }

  @Test
  void embeddingAndIngestionProgressBarsShareColumnAndWidth() {
    String ingested = ConsoleProgress.renderFinite("Ingested source files", 1, 1, 0, false);
    String codeChunk = ConsoleProgress.renderIndeterminate("Refreshing Code RAG", 0, false);
    String memoryChunk = ConsoleProgress.renderIndeterminate("Refreshing Memory RAG", 0, false);

    assertAlignedProgressBar(ingested, codeChunk);
    assertAlignedProgressBar(ingested, memoryChunk);
    assertTrue(codeChunk.contains("[==>"));
    assertTrue(memoryChunk.contains("[==>"));
  }

  @Test
  void longRuntimeLabelsShareProgressBarColumn() {
    String ingested = ConsoleProgress.renderFinite("Ingested source files", 186, 186, 0, false);
    String cpython =
        ConsoleProgress.renderIndeterminate(
            "Loading managed runtime: CPython 3.14.5+20260510", 0, false);

    assertAlignedProgressBar(ingested, cpython);
    assertTrue(cpython.contains("Loading managed runtime: CPython"));
    assertTrue(cpython.contains("[==>"));
    assertFalse(cpython.contains("aarch64-apple-darwin"));
  }

  @Test
  void indeterminateProgressUsesOneInteractiveStatusLine() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (var _ =
        ConsoleProgress.indeterminate("Resolving graph", out, true, Duration.ofSeconds(60), true)) {
      assertTrue(ConsoleStatusLine.hasExclusiveStatus(out));
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("\r"));
    assertTrue(output.contains("Resolving graph"));
    assertFalse(ConsoleStatusLine.hasExclusiveStatus(out));
  }

  @Test
  void indeterminateProgressCanCompleteOnSameStatusLine() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ConsoleProgress progress =
        ConsoleProgress.indeterminate("Resolving graph", out, true, Duration.ofSeconds(60), true)) {
      progress.complete("Resolved graph");
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("\rResolved graph"));
    assertTrue(ConsoleStatusLine.hasActiveLine(out));
    ConsoleStatusLine.finish(out);
    assertFalse(ConsoleStatusLine.hasExclusiveStatus(out));
  }

  @Test
  void indeterminateProgressCanBeDiscardedWithoutLeavingStatusLine() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ConsoleProgress progress =
        ConsoleProgress.indeterminate("Resolving graph", out, true, Duration.ofSeconds(60), true)) {
      progress.discard();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Resolving graph"));
    assertFalse(ConsoleStatusLine.hasActiveLine(out));
    assertFalse(ConsoleStatusLine.hasExclusiveStatus(out));
    assertFalse(output.endsWith("\n"));
  }

  private static void assertAlignedProgressBar(String expected, String actual) {
    int expectedStart = expected.indexOf('[');
    int actualStart = actual.indexOf('[');
    assertTrue(expectedStart > 0);
    assertTrue(actualStart > 0);
    assertTrue(expected.indexOf(']') > expectedStart);
    assertTrue(actual.indexOf(']') > actualStart);
    assertTrue(expected.contains("[=============================]"));
    assertEquals(expectedStart, actualStart);
    assertEquals(
        expected.indexOf(']') - expectedStart,
        actual.indexOf(']') - actualStart,
        () -> expected + "\n" + actual);
  }
}
