package io.github.ousatov.tools.memgraph.exe.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests simplelogger file naming and append behavior.
 *
 * @author Oleksii Usatov
 */
class SimpleLoggerFileTest {

  @TempDir private Path tempDir;

  @Test
  void datedLogFileIncludesIsoDate() {
    Path logFile = SimpleLoggerFile.datedLogFile(LocalDate.of(2026, 6, 1));

    assertEquals(Path.of("memgraph-ingester-2026-06-01.log"), logFile);
  }

  @Test
  void appendStreamDoesNotOverwriteExistingLogFile() throws Exception {
    Path logFile = tempDir.resolve("memgraph-ingester-2026-06-01.log");
    Files.writeString(logFile, "before\n");

    try (var out = SimpleLoggerFile.openAppendStream(logFile)) {
      out.println("after");
    }

    String content = Files.readString(logFile);
    assertTrue(content.contains("before\n"));
    assertTrue(content.endsWith("after\n"));
  }
}
