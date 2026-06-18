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
    assertTrue(rendered.endsWith("50%"));
    assertFalse(rendered.contains("5/10"), "raw done/total counts are hidden");
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
  void finiteRendererUsesWholeCellUnicodeBarInColorMode() {
    String rendered = ConsoleProgress.renderFinite("Installing packages", 5, 10, 0, true);

    assertTrue(rendered.contains("\u280B"), "braille spinner frame");
    assertTrue(rendered.contains("\u2588"), "full block fill");
    assertTrue(rendered.contains("\u2591"), "dim track");
    assertFalse(
        rendered.contains("\u258C"), "no eighth-block sub-cell glyph between fill and track");
    assertTrue(rendered.contains("50%"));
    assertFalse(rendered.contains("5/10"), "raw done/total counts are hidden");
  }

  @Test
  void indeterminateRendererUsesCometOverTrackInColorMode() {
    String rendered = ConsoleProgress.renderIndeterminate("Resolving graph", 0, true);

    assertTrue(rendered.contains("\u280B"), "braille spinner frame");
    assertTrue(rendered.contains("\u2588"), "bright comet segment");
    assertTrue(rendered.contains("\u2591"), "dim track");
    assertTrue(rendered.contains("Resolving graph"));
  }

  @Test
  void completeRendererUsesCheckMarkAndFullUnicodeBarInColorMode() {
    String rendered = ConsoleProgress.renderComplete("Loaded managed runtime: Node.js", true);

    assertTrue(rendered.contains("\u2713"), "check mark");
    assertTrue(rendered.contains("\u2588".repeat(29)), "full unicode bar");
    assertTrue(rendered.contains("\u001B[32m"), "green success accent");
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

  @Test
  void indeterminateProgressPrintsInitialLineInLogMode() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ConsoleProgress progress =
        ConsoleProgress.indeterminate(
            "Finalizing graph", out, false, Duration.ofSeconds(60), true)) {
      progress.discard();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Finalizing graph"));
    assertTrue(output.contains("[==>"));
    assertTrue(output.endsWith("\n"));
  }

  @Test
  void finiteProgressAnimatesWithoutUpdates() throws InterruptedException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (var _ = ConsoleProgress.finite("Scanning source files", 10, out, true, false)) {
      waitForOutput(bytes, "0%");
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Scanning source files"));
    assertTrue(output.contains("0%"));
    assertFalse(output.contains("0/10"), "raw done/total counts are hidden");
  }

  @Test
  @SuppressWarnings("java:S2925")
  void finiteProgressWaitsDuringExclusiveStatus() throws InterruptedException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (var _ = ConsoleStatusLine.openExclusiveStatusSession(out)) {
      try (var _ = ConsoleProgress.finite("Scanning source files", 10, out, true, false)) {
        Thread.sleep(250);
      }
    }

    assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("0%"));
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

  @SuppressWarnings("java:S2925")
  private static void waitForOutput(ByteArrayOutputStream bytes, String expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() < deadline
        && !bytes.toString(StandardCharsets.UTF_8).contains(expected)) {
      Thread.sleep(20);
    }
  }
}
