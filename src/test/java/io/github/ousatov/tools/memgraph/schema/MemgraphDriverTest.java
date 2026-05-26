package io.github.ousatov.tools.memgraph.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

/** Unit tests for {@link MemgraphDriver}. */
class MemgraphDriverTest {

  @Test
  void openDoesNotWriteNeo4jDriverStartupMessageToStderr() {
    PrintStream originalErr = System.err;
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    try (PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
      System.setErr(capturedErr);
      try (Driver driver = MemgraphDriver.open("bolt://127.0.0.1:1")) {
        assertNotNull(driver);
      }
    } finally {
      System.setErr(originalErr);
    }

    String output = stderr.toString(StandardCharsets.UTF_8);
    assertFalse(output.contains("Driver instance"));
    assertFalse(output.contains("org.neo4j.driver.internal.logging.SystemLogger"));
  }
}
