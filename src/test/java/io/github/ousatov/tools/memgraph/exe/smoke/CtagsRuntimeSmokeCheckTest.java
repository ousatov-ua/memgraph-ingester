package io.github.ousatov.tools.memgraph.exe.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CtagsRuntimeSmokeCheck} metadata accessors.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings("java:S5443")
class CtagsRuntimeSmokeCheckTest {

  @Test
  void displayMetadataExposedForLogging() {
    CtagsRuntimeSmokeCheck check =
        new CtagsRuntimeSmokeCheck(Path.of("/tmp/ctags-cache"), "v6.1.0", RuntimeMode.MANAGED);

    assertEquals("Universal Ctags", check.displayName());
    assertTrue(check.tempDirPrefix().startsWith("memgraph-ingester-ctags-"));
    assertEquals(Path.of("/tmp/ctags-cache"), check.cacheRoot());
  }
}
