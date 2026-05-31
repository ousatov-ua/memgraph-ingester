package io.github.ousatov.tools.memgraph.exe.ingestion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests watch-mode console status output.
 *
 * @author Oleksii Usatov
 */
class WatchSessionTest {

  @Test
  void watchStatusDoesNotAddLogPrefix() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    try {
      WatchSession.renderWatchStatus("Watch re-ingestion applied 3 times.");
    } finally {
      System.setErr(originalErr);
    }

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Watch re-ingestion applied 3 times."));
    assertFalse(output.contains("INFO"));
    assertFalse(output.contains("[Stat]"));
  }
}
