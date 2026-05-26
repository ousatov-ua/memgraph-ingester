package io.github.ousatov.tools.memgraph.exe.ingestion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IngestionProgressTest {

  @Test
  void interactiveModeUpdatesProgressOnOneConsoleRow() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    assertFalse(ConsoleStatusLine.hasActiveStatus(out));
    try (IngestionProgress progress = IngestionProgress.start(64, out, true)) {
      assertTrue(ConsoleStatusLine.hasActiveStatus(out));
      progress.update(1);
      progress.update(6);
      progress.update(12);
      progress.update(64);
    }
    assertFalse(ConsoleStatusLine.hasActiveStatus(out));

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Progress: 6/64"));
    assertTrue(output.contains("Progress: 64/64"));
    assertTrue(output.contains("\r"));
    assertFalse(output.contains("files"));
    assertTrue(output.chars().filter(ch -> ch == '\n').count() <= 1);
  }

  @Test
  void logModeWritesOnlyProgressMilestoneLines() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (IngestionProgress progress = IngestionProgress.start(64, out, false)) {
      progress.update(1);
      progress.update(6);
      progress.update(12);
      progress.update(64);
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertFalse(output.contains("Progress: 1/64"));
    assertTrue(output.contains("Progress: 6/64"));
    assertTrue(output.contains("Progress: 12/64"));
    assertTrue(output.contains("Progress: 64/64"));
    assertFalse(output.contains("\r"));
    assertTrue(output.lines().allMatch(line -> line.startsWith("Progress: ")));
  }

  @Test
  void interactiveModeDoesNotRefreshProgressDuringExclusiveRuntimeStatus() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (IngestionProgress progress = IngestionProgress.start(64, out, true)) {
      try (var _ = ConsoleStatusLine.openExclusiveStatusSession(out)) {
        progress.update(6);
      }
      progress.update(12);
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertFalse(output.contains("Progress: 6/64"));
    assertTrue(output.contains("Progress: 12/64"));
  }
}
