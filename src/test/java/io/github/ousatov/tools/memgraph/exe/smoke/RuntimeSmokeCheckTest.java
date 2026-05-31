package io.github.ousatov.tools.memgraph.exe.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for the {@link RuntimeSmokeCheck} template-method scaffold.
 *
 * @author Oleksii Usatov
 */
class RuntimeSmokeCheckTest {

  @Test
  void runReturnsZeroAndCleansUpTempDirOnSuccess() {
    RecordingCheck check = new RecordingCheck(false, false);

    int exit = check.run();

    assertEquals(0, exit);
    assertNotNull(check.executedTempDir);
    assertFalse(Files.exists(check.executedTempDir));
  }

  @Test
  void runReturnsOneWhenExecuteThrowsRuntimeException() {
    RecordingCheck check = new RecordingCheck(true, false);

    int exit = check.run();

    assertEquals(1, exit);
    assertFalse(Files.exists(check.executedTempDir));
  }

  @Test
  void runReturnsOneWhenExecuteThrowsIoException() {
    RecordingCheck check = new RecordingCheck(false, true);

    int exit = check.run();

    assertEquals(1, exit);
    assertFalse(Files.exists(check.executedTempDir));
  }

  @Test
  void runRemovesNestedFilesInTempDir() {
    Path[] capturedNested = new Path[1];
    RuntimeSmokeCheck check =
        new RuntimeSmokeCheck(LoggerFactory.getLogger(RuntimeSmokeCheckTest.class)) {
          @Override
          protected String displayName() {
            return "Recording";
          }

          @Override
          protected String tempDirPrefix() {
            return "smoke-check-nested-";
          }

          @Override
          protected Path cacheRoot() {
            return Path.of("/tmp/cache");
          }

          @Override
          protected void execute(Path tempDir) throws IOException {
            Path nested = tempDir.resolve("nested/inner.txt");
            Files.createDirectories(nested.getParent());
            Files.writeString(nested, "payload");
            capturedNested[0] = nested;
          }
        };

    int exit = check.run();

    assertEquals(0, exit);
    assertFalse(Files.exists(capturedNested[0]));
  }

  private static final class RecordingCheck extends RuntimeSmokeCheck {

    private final boolean throwRuntime;
    private final boolean throwIo;
    private Path executedTempDir;

    RecordingCheck(boolean throwRuntime, boolean throwIo) {
      super(LoggerFactory.getLogger(RecordingCheck.class));
      this.throwRuntime = throwRuntime;
      this.throwIo = throwIo;
    }

    @Override
    protected String displayName() {
      return "Recording";
    }

    @Override
    protected String tempDirPrefix() {
      return "smoke-check-recording-";
    }

    @Override
    protected Path cacheRoot() {
      return Path.of("/tmp/recording-cache");
    }

    @Override
    protected void execute(Path tempDir) throws IOException {
      this.executedTempDir = tempDir;
      if (throwRuntime) {
        throw new IllegalStateException("recorded runtime failure");
      }
      if (throwIo) {
        throw new IOException("recorded io failure");
      }
    }
  }
}
