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
}
