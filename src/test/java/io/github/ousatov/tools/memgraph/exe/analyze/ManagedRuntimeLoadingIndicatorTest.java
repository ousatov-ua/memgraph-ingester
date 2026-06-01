package io.github.ousatov.tools.memgraph.exe.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests managed runtime loading messages.
 *
 * @author Oleksii Usatov
 */
class ManagedRuntimeLoadingIndicatorTest {

  @Test
  void logModePrintsLoadingAndLoadedWithoutAnimationSpam() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.start("test runtime", out, Duration.ofMillis(5))) {
      indicator.succeeded();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertFalse(output.contains("\r"));
    assertEquals(
        List.of(
            "Loading managed runtime: test runtime...", "Loaded managed runtime: test runtime."),
        output.lines().toList());
  }

  @Test
  void interactiveModePrintsLoadingThenCompletedRuntimeStatusOnOneRow() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.start("test runtime", out, Duration.ofSeconds(60), true)) {
      assertTrue(ConsoleStatusLine.hasExclusiveStatus(out));
      indicator.succeeded();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("\r"));
    assertTrue(output.contains("Loading managed runtime: test runtime"));
    assertTrue(output.contains("[==>"));
    assertTrue(output.contains("Loaded managed runtime: test runtime"));
    assertTrue(output.contains("[=============================]"));
    assertFalse(output.contains("Loaded managed runtime: test runtime."));
    assertEquals(1, output.chars().filter(ch -> ch == '\n').count());
    assertFalse(ConsoleStatusLine.hasExclusiveStatus(out));
  }

  @Test
  void steadyInteractiveModePrintsSamePlainLines() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.steady("test runtime", out, Duration.ofMillis(5), true)) {
      indicator.succeeded();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertFalse(output.contains("\r"));
    assertEquals(
        List.of(
            "Loading managed runtime: test runtime...", "Loaded managed runtime: test runtime."),
        output.lines().toList());
  }

  @Test
  void animationFlagPrintsSingleLoadingFrameThenCompletedRuntimeStatus() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.start(
            "test runtime", out, Duration.ofSeconds(60), true, true)) {
      indicator.succeeded();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("\r"));
    assertTrue(output.contains("Loading managed runtime: test runtime"));
    assertTrue(output.contains("[==>"));
    assertTrue(output.contains("Loaded managed runtime: test runtime"));
    assertTrue(output.contains("[=============================]"));
    assertFalse(output.contains("Loaded managed runtime: test runtime."));
    assertEquals(1, output.chars().filter(ch -> ch == '\n').count());
  }

  @Test
  void logModePrintsFailedWhenClosedWithoutSuccess() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ManagedRuntimeLoadingIndicator ignored =
        ManagedRuntimeLoadingIndicator.start("test runtime", out, Duration.ofMillis(5))) {
      // Closing without marking success should leave a visible failure status.
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertFalse(output.contains("\r"));
    assertEquals(
        List.of(
            "Loading managed runtime: test runtime...",
            "Failed to load managed runtime: test runtime."),
        output.lines().toList());
  }
}
