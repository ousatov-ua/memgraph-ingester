package io.github.ousatov.tools.memgraph.exe.analyze;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ManagedRuntimeLoadingIndicatorTest {

  @Test
  void logModePrintsStartAndDoneWithoutAnimationSpam() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.start("test runtime", out, Duration.ofMillis(5))) {
      Thread.sleep(20);
      indicator.succeeded();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Loading managed runtime: test runtime..."));
    assertTrue(output.contains("done."));
    assertFalse(output.contains("\r"));
    assertTrue(output.lines().allMatch(line -> line.startsWith("Loading managed runtime: ")));
    assertTrue(output.lines().count() <= 2);
  }

  @Test
  void interactiveModeCyclesDotsInPlaceAndPrintsDoneWhenSucceeded() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.start("test runtime", out, Duration.ofMillis(5), true)) {
      assertTrue(ConsoleStatusLine.hasExclusiveStatus(out));
      Thread.sleep(20);
      indicator.succeeded();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Loading managed runtime: test runtime"));
    assertTrue(output.contains(".."));
    assertTrue(output.contains("done."));
    assertTrue(output.contains("\r"));
    assertTrue(output.chars().filter(ch -> ch == '\n').count() <= 1);
    assertFalse(ConsoleStatusLine.hasExclusiveStatus(out));
  }

  @Test
  void steadyInteractiveModeDoesNotAnimateDotsInPlace() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.steady("test runtime", out, Duration.ofMillis(5), true)) {
      Thread.sleep(20);
      indicator.succeeded();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Loading managed runtime: test runtime..."));
    assertTrue(output.contains("done."));
    assertFalse(output.contains("\r"));
    assertTrue(output.lines().count() <= 2);
  }

  @Test
  void interactiveModeCanDisableAnimationAfterProgressLineWasInterrupted() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (ManagedRuntimeLoadingIndicator indicator =
        ManagedRuntimeLoadingIndicator.start(
            "test runtime", out, Duration.ofMillis(5), true, false)) {
      Thread.sleep(20);
      indicator.succeeded();
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Loading managed runtime: test runtime..."));
    assertTrue(output.contains("done."));
    assertFalse(output.contains("\r"));
    assertTrue(output.lines().count() <= 2);
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
    assertTrue(output.contains("Loading managed runtime: test runtime"));
    assertTrue(output.contains("failed."));
    assertFalse(output.contains("\r"));
    assertTrue(output.lines().count() <= 2);
  }
}
