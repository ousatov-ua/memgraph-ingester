package io.github.ousatov.tools.memgraph.exe.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.def.Const;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests Memgraph ingester console banner output.
 *
 * @author Oleksii Usatov
 */
class ConsoleOutputTest {

  private static final String HIDE_CURSOR = "\u001B[?25l";
  private static final String SHOW_CURSOR = "\u001B[?25h";

  @Test
  void titleBannerPrintsExpectedApplicationName() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    ConsoleOutput.printTitle(out, false);

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains(ConsoleOutput.TITLE));
    assertTrue(output.contains("version " + Const.Cli.VERSION));
    assertTrue(output.startsWith("+---"));
    assertTrue(output.lines().anyMatch(line -> line.startsWith("|") && line.endsWith("|")));
    assertEquals(4, output.lines().count());
  }

  @Test
  void persistentLinePreservesActiveStatusLine() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    ConsoleOutput.status(out, "Applied schema to Memgraph");
    ConsoleOutput.line(out, "Watch mode for src activated.");

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Applied schema to Memgraph\nWatch mode for src activated.\n"));
  }

  @Test
  void interactiveTitleHidesCursorJustAfterOutputStartsUntilShutdown() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    ConsoleOutput.printTitle(out, true);

    String titleOutput = bytes.toString(StandardCharsets.UTF_8);
    int firstLineBreak = titleOutput.indexOf('\n');
    int hideCursor = titleOutput.indexOf(HIDE_CURSOR);
    assertTrue(firstLineBreak >= 0);
    assertTrue(hideCursor > firstLineBreak);
    assertEquals(1, countOccurrences(titleOutput, HIDE_CURSOR));
    assertTrue(titleOutput.contains(ConsoleOutput.TITLE));

    ConsoleStatusLine.restoreCursorForShutdown();

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertEquals(1, countOccurrences(output, HIDE_CURSOR));
    assertEquals(1, countOccurrences(output, SHOW_CURSOR));
    assertTrue(output.endsWith(SHOW_CURSOR));
  }

  @Test
  void successUsesMutedGreenAccent() {
    String styled = AnsiStyle.success("Watch mode for src activated.", true);

    assertTrue(styled.contains("\u001B[32m"));
    assertTrue(styled.contains("Watch mode for src activated."));
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
