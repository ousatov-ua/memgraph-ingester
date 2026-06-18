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
@SuppressWarnings("java:S106")
final class IngestionProgress implements AutoCloseable {

  private static final int PROGRESS_DIVISOR = 20;

  private final int total;
  private final int step;
  private final PrintStream out;
  private final boolean interactive;
  private final ConsoleProgress progress;

  private int done;
  private int renderedDone;
  private boolean renderedCompletion;
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
    this.progress =
        ConsoleProgress.finite("Ingesting source files", total, out, interactive, interactive);
  }

  void update(int done) {
    if (closed) {
      return;
    }
    int clampedDone = Math.clamp(done, 0, total);
    this.done = Math.max(this.done, clampedDone);
    if (total == 0 || (clampedDone < renderedDone + step && clampedDone != total)) {
      return;
    }
    if (interactive && ConsoleStatusLine.hasExclusiveStatus(out)) {
      return;
    }
    render(clampedDone);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    boolean needsCompletionLabel = done == total && !renderedCompletion;
    if ((done != renderedDone || needsCompletionLabel)
        && !(interactive && ConsoleStatusLine.hasExclusiveStatus(out))) {
      render(done);
    }
    progress.close();
  }

  private void render(int done) {
    String label = done == total ? "Ingested source files" : "Ingesting source files";
    progress.update(label, done);
    renderedDone = done;
    renderedCompletion = done == total;
  }
}
