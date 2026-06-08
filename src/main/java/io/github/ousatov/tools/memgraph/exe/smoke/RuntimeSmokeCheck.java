package io.github.ousatov.tools.memgraph.exe.smoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.slf4j.Logger;

/**
 * Common scaffolding for parser-runtime smoke checks invoked from the CLI.
 *
 * <p>Manages a temporary fixture directory, error-to-exit-code translation, and shared cleanup so
 * each language-specific subclass only writes fixtures and asserts on its analyzer's output.
 *
 * @author Oleksii Usatov
 */
public abstract class RuntimeSmokeCheck {

  private final Logger log;

  protected RuntimeSmokeCheck(Logger log) {
    this.log = log;
  }

  /** Runs the smoke check. Returns 0 on success, 1 on failure. */
  public final int run() {
    Path tempDir = null;
    try {
      Path tempRoot = Files.createDirectories(cacheRoot().resolve("smoke-checks"));
      tempDir = Files.createTempDirectory(tempRoot, tempDirPrefix());
      execute(tempDir);
      if (log.isInfoEnabled()) {
        log.info("{} runtime check succeeded using cache {}", displayName(), cacheRoot());
      }
      return 0;
    } catch (IOException | RuntimeException e) {
      log.error("{} runtime check failed: {}", displayName(), e.getMessage());
      return 1;
    } finally {
      if (tempDir != null) {
        deleteDir(tempDir);
      }
    }
  }

  /** Human-readable name used in log messages, e.g. {@code "JavaScript parser"}. */
  protected abstract String displayName();

  /**
   * Prefix passed to {@link Files#createTempDirectory(String,
   * java.nio.file.attribute.FileAttribute[])}.
   */
  protected abstract String tempDirPrefix();

  /** Cache root location reported alongside success log messages. */
  protected abstract Path cacheRoot();

  /** Executes the language-specific fixture writes and assertions. */
  protected abstract void execute(Path tempDir) throws IOException;

  private void deleteDir(Path root) {
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  log.warn("Could not delete temporary path {}: {}", path, e.getMessage());
                }
              });
    } catch (IOException e) {
      log.warn("Could not clean temporary directory {}: {}", root, e.getMessage());
    }
  }
}
