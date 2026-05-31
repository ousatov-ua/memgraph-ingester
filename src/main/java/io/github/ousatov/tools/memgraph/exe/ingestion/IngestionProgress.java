package io.github.ousatov.tools.memgraph.exe.ingestion;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleProgress;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import java.io.PrintStream;
import java.util.Objects;

/**
 * Renders file-ingestion progress without timestamped log-line spam.
 *
 * @author Oleksii Usatov
 */
final class IngestionProgress implements AutoCloseable {

  private static final int PROGRESS_DIVISOR = 10;

  private final int total;
  private final int step;
  private final PrintStream out;
  private final boolean interactive;
  private final ConsoleProgress progress;

  private volatile boolean closed;

  static IngestionProgress start(int total) {
    return start(total, System.err, ConsoleStatusLine.isInteractive());
  }

  static IngestionProgress start(int total, PrintStream out, boolean interactive) {
    return new IngestionProgress(total, out, interactive);
  }

  private IngestionProgress(int total, PrintStream out, boolean interactive) {
    this.total = total;
    this.step = Math.clamp(total / PROGRESS_DIVISOR, 1, 100);
    this.out = Objects.requireNonNull(out, Const.Params.OUT);
    this.interactive = interactive;
    this.progress = ConsoleProgress.finite("Ingesting source files", total, out, interactive);
  }

  void update(int done) {
    if (closed || total == 0 || (done % step != 0 && done != total)) {
      return;
    }
    if (interactive && ConsoleStatusLine.hasExclusiveStatus(out)) {
      return;
    }
    String label = done == total ? "Ingested source files" : "Ingesting source files";
    progress.update(label, done);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    progress.close();
  }
}
