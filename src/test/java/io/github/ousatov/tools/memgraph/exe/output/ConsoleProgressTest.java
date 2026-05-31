package io.github.ousatov.tools.memgraph.exe.output;

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
    String backward = ConsoleProgress.renderIndeterminate("Resolving graph", 24, false);

    assertTrue(forward.contains("[==>"));
    assertTrue(backward.contains("<=="));
    assertNotEquals(forward, backward);
  }

  @Test
  void indeterminateProgressUsesOneInteractiveStatusLine() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ConsoleProgress _ =
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
}
