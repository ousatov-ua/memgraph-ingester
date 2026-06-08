package io.github.ousatov.tools.memgraph.exe.output;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Boots {@code slf4j-simple} with an append-only dated log file.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings("java:S106")
public final class SimpleLoggerFile {

  private static final String LOG_FILE_PROPERTY = "org.slf4j.simpleLogger.logFile";
  private static final String CACHE_OUTPUT_STREAM_PROPERTY =
      "org.slf4j.simpleLogger.cacheOutputStream";
  private static final DateTimeFormatter LOG_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  private final PrintStream originalErr;
  private final PrintStream logStream;

  private SimpleLoggerFile(PrintStream originalErr, PrintStream logStream) {
    this.originalErr = originalErr;
    this.logStream = logStream;
  }

  /**
   * Temporarily points {@link System#err} at the append-only log stream so simplelogger can cache
   * it during initialization.
   */
  public static SimpleLoggerFile configure() {
    PrintStream originalErr = System.err;
    try {
      PrintStream logStream = openAppendStream(datedLogFile(LocalDate.now()));
      System.setProperty(LOG_FILE_PROPERTY, "System.err");
      System.setProperty(CACHE_OUTPUT_STREAM_PROPERTY, "true");
      System.setErr(logStream);
      return new SimpleLoggerFile(originalErr, logStream);
    } catch (FileNotFoundException e) {
      originalErr.println("Could not open dated Memgraph ingester log file: " + e.getMessage());
      return new SimpleLoggerFile(originalErr, null);
    }
  }

  /** Restores the original console error stream after simplelogger has cached the log stream. */
  public void restoreConsole() {
    if (logStream != null && System.err == logStream) {
      System.setErr(originalErr);
    }
  }

  static Path datedLogFile(LocalDate date) {
    return Path.of("memgraph-ingester-" + LOG_DATE.format(date) + ".log");
  }

  static PrintStream openAppendStream(Path path) throws FileNotFoundException {
    return new PrintStream(new FileOutputStream(path.toFile(), true), true, StandardCharsets.UTF_8);
  }
}
