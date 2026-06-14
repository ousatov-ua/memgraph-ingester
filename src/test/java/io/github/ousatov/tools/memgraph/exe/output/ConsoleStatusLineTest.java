package io.github.ousatov.tools.memgraph.exe.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConsoleStatusLineTest {

  private static final String HIDE_CURSOR = "\u001B[?25l";
  private static final String SHOW_CURSOR = "\u001B[?25h";

  @Test
  void lineClearsActiveStatusBeforePrintingMessage() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    ConsoleStatusLine.update(out, "Progress: 56/86");
    ConsoleStatusLine.line(out, "Loading managed runtime: CPython...");

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Progress: 56/86"));
    assertTrue(output.contains("Loading managed runtime: CPython..."));
    assertFalse(output.contains("56/86Loading"));
    assertTrue(output.endsWith("Loading managed runtime: CPython...\n"));
  }

  @Test
  void finishEndsActiveStatusLineOnce() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    ConsoleStatusLine.update(out, "Progress: 64/64");
    ConsoleStatusLine.finish(out);
    ConsoleStatusLine.finish(out);

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Progress: 64/64"));
    assertTrue(output.endsWith("\n"));
    assertEquals(1, output.chars().filter(ch -> ch == '\n').count());
  }

  @Test
  void repeatedUpdatesReuseSameConsoleRow() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    ConsoleStatusLine.update(
        out, "INFO Watch event: detected changes in 1 file(s). Re-ingesting...");
    ConsoleStatusLine.update(out, "INFO [Stat] Watch re-ingestion applied 2 times.");
    ConsoleStatusLine.finish(out);

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(
        output.contains("\rINFO Watch event: detected changes in 1 file(s). Re-ingesting..."));
    assertTrue(output.contains("\rINFO [Stat] Watch re-ingestion applied 2 times."));
    assertEquals(1, output.chars().filter(ch -> ch == '\n').count());
    assertTrue(
        output
            .replaceAll(" +\\n$", "\n")
            .endsWith("INFO [Stat] Watch re-ingestion applied 2 times.\n"));
  }

  @Test
  void repeatedUpdatesClearByVisibleLengthWhenTextHasAnsiCodes() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    ConsoleStatusLine.update(out, "1234567890");
    ConsoleStatusLine.update(out, "\u001B[32m12345\u001B[0m");

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("\r\u001B[32m12345\u001B[0m     "));
    assertEquals(5, ConsoleStatusLine.visibleLength("\u001B[32m12345\u001B[0m"));
  }

  @Test
  void activeLineStateIsVisibleAndReportedWhenFinished() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    assertFalse(ConsoleStatusLine.hasActiveLine(out));
    assertFalse(ConsoleStatusLine.finishIfActive(out));

    ConsoleStatusLine.update(out, "Progress: 45/90");

    assertTrue(ConsoleStatusLine.hasActiveLine(out));
    assertTrue(ConsoleStatusLine.finishIfActive(out));
    assertFalse(ConsoleStatusLine.hasActiveLine(out));
    assertFalse(ConsoleStatusLine.finishIfActive(out));
  }

  @Test
  void externalLineActionGetsCleanLineAndReportsInterruptedStatus() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    ConsoleStatusLine.update(out, "Progress: 45/90");
    boolean finished = ConsoleStatusLine.withFinishedLine(out, () -> out.println("INFO Checking"));

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(finished);
    assertTrue(output.contains("Progress: 45/90\nINFO Checking\n"));
    assertFalse(output.contains("45/90INFO"));
    assertFalse(ConsoleStatusLine.hasActiveLine(out));
  }

  @Test
  void statusSessionReportsActivityBeforeFirstRenderedLine() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    assertFalse(ConsoleStatusLine.hasActiveStatus(out));
    try (var _ = ConsoleStatusLine.openStatusSession(out)) {
      assertTrue(ConsoleStatusLine.hasActiveStatus(out));
      assertTrue(ConsoleStatusLine.withFinishedLine(out, () -> out.println("INFO Checking")));
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("INFO Checking\n"));
    assertFalse(ConsoleStatusLine.hasActiveStatus(out));
  }

  @Test
  void exclusiveStatusSessionIsTrackedSeparately() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    assertFalse(ConsoleStatusLine.hasExclusiveStatus(out));
    try (var _ = ConsoleStatusLine.openExclusiveStatusSession(out)) {
      assertTrue(ConsoleStatusLine.hasActiveStatus(out));
      assertTrue(ConsoleStatusLine.hasExclusiveStatus(out));
    }

    assertFalse(ConsoleStatusLine.hasActiveStatus(out));
    assertFalse(ConsoleStatusLine.hasExclusiveStatus(out));
  }

  @Test
  void statusSessionHidesCursorAfterStatusOutputStartsUntilFinalSessionCloses() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (var _ = ConsoleStatusLine.openStatusSession(out)) {
      assertFalse(bytes.toString(StandardCharsets.UTF_8).contains(HIDE_CURSOR));

      ConsoleStatusLine.update(out, "Progress: 1/2");

      try (var _ = ConsoleStatusLine.openExclusiveStatusSession(out)) {
        assertTrue(ConsoleStatusLine.hasActiveStatus(out));
        assertTrue(ConsoleStatusLine.hasExclusiveStatus(out));
        ConsoleStatusLine.update(out, "Progress: 2/2");
      }

      String nestedOutput = bytes.toString(StandardCharsets.UTF_8);
      assertTrue(nestedOutput.startsWith("\r"));
      assertTrue(nestedOutput.indexOf(HIDE_CURSOR) > 0);
      assertEquals(1, countOccurrences(nestedOutput, HIDE_CURSOR));
      assertFalse(nestedOutput.contains(SHOW_CURSOR));
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertEquals(1, countOccurrences(output, HIDE_CURSOR));
    assertEquals(1, countOccurrences(output, SHOW_CURSOR));
    assertTrue(output.endsWith(SHOW_CURSOR));
    assertTrue(ConsoleStatusLine.hasActiveLine(out));
    ConsoleStatusLine.finish(out);
    assertFalse(ConsoleStatusLine.hasActiveStatus(out));
  }

  @Test
  void shutdownRestoreShowsCursorAndClearsStatusSessions() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    try (var _ = ConsoleStatusLine.openStatusSession(out)) {
      assertTrue(ConsoleStatusLine.hasActiveStatus(out));
      ConsoleStatusLine.update(out, "Progress: 1/2");

      ConsoleStatusLine.restoreCursorForShutdown();

      assertFalse(ConsoleStatusLine.hasActiveStatus(out));
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertEquals(1, countOccurrences(output, HIDE_CURSOR));
    assertEquals(1, countOccurrences(output, SHOW_CURSOR));
    assertTrue(output.endsWith(SHOW_CURSOR));
  }

  @Test
  void directStatusUpdateDoesNotToggleCursorWithoutSession() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    ConsoleStatusLine.update(out, "Progress: 1/2");
    ConsoleStatusLine.finish(out);

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertFalse(output.contains(HIDE_CURSOR));
    assertFalse(output.contains(SHOW_CURSOR));
  }

  private static int countOccurrences(String text, String value) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(value, index)) >= 0) {
      count++;
      index += value.length();
    }
    return count;
  }
}
