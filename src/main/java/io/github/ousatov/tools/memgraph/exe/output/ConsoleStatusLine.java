package io.github.ousatov.tools.memgraph.exe.output;

import io.github.ousatov.tools.memgraph.def.Const;
import java.io.PrintStream;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Coordinates status-line output that rewrites the current console row.
 *
 * @author Oleksii Usatov
 */
public final class ConsoleStatusLine {

  private static final Object LOCK = new Object();

  private static PrintStream activeOut;
  private static int lastLength;
  private static boolean active;
  private static final Map<PrintStream, Integer> statusSessions = new IdentityHashMap<>();
  private static final Map<PrintStream, Integer> exclusiveStatusSessions = new IdentityHashMap<>();

  private ConsoleStatusLine() {
    throw new UnsupportedOperationException("Utility class");
  }

  public static boolean isInteractive() {
    return System.console() != null;
  }

  public static boolean hasActiveLine(PrintStream out) {
    synchronized (LOCK) {
      return active && activeOut == Objects.requireNonNull(out, Const.Params.OUT);
    }
  }

  public static boolean hasActiveStatus(PrintStream out) {
    synchronized (LOCK) {
      return hasActiveStatusFor(Objects.requireNonNull(out, Const.Params.OUT));
    }
  }

  public static boolean hasExclusiveStatus(PrintStream out) {
    synchronized (LOCK) {
      return exclusiveStatusSessions.containsKey(Objects.requireNonNull(out, Const.Params.OUT));
    }
  }

  public static StatusSession openStatusSession(PrintStream out) {
    return openStatusSession(out, false);
  }

  public static StatusSession openExclusiveStatusSession(PrintStream out) {
    return openStatusSession(out, true);
  }

  private static StatusSession openStatusSession(PrintStream out, boolean exclusive) {
    PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
    synchronized (LOCK) {
      statusSessions.merge(stream, 1, Integer::sum);
      if (exclusive) {
        exclusiveStatusSessions.merge(stream, 1, Integer::sum);
      }
    }
    return new StatusSession(stream, exclusive);
  }

  public static void update(PrintStream out, String text) {
    Objects.requireNonNull(text, Const.Params.TEXT);
    synchronized (LOCK) {
      PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
      clearDifferentActiveStream(stream);
      int visibleLength = visibleLength(text);
      stream.print('\r');
      clearLine(stream);
      stream.print(text);
      if (lastLength > visibleLength) {
        stream.print(Const.Symbols.SPACE.repeat(lastLength - visibleLength));
      }
      lastLength = visibleLength;
      active = true;
      activeOut = stream;
      stream.flush();
    }
  }

  public static void line(PrintStream out, String text) {
    Objects.requireNonNull(text, Const.Params.TEXT);
    synchronized (LOCK) {
      PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
      clearActiveLine(stream);
      stream.println(text);
      stream.flush();
    }
  }

  public static void finish(PrintStream out) {
    finishIfActive(out);
  }

  public static void clear(PrintStream out) {
    synchronized (LOCK) {
      PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
      clearActiveLine(stream);
    }
  }

  public static boolean finishIfActive(PrintStream out) {
    synchronized (LOCK) {
      PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
      return finishActiveLine(stream);
    }
  }

  public static boolean withFinishedLine(PrintStream out, Runnable action) {
    Objects.requireNonNull(action, "action");
    synchronized (LOCK) {
      PrintStream stream = Objects.requireNonNull(out, Const.Params.OUT);
      boolean hadStatus = hasActiveStatusFor(stream);
      finishActiveLine(stream);
      action.run();
      stream.flush();
      return hadStatus;
    }
  }

  private static boolean hasActiveStatusFor(PrintStream stream) {
    return (active && activeOut == stream) || statusSessions.containsKey(stream);
  }

  private static boolean finishActiveLine(PrintStream stream) {
    if (active && activeOut == stream) {
      stream.println();
      reset();
      stream.flush();
      return true;
    }
    return false;
  }

  private static void clearDifferentActiveStream(PrintStream stream) {
    if (active && activeOut != stream) {
      PrintStream previous = activeOut;
      previous.println();
      previous.flush();
      reset();
    }
  }

  private static void clearActiveLine(PrintStream stream) {
    if (!active) {
      return;
    }
    if (activeOut == stream) {
      stream.print('\r');
      clearLine(stream);
    } else {
      PrintStream previous = activeOut;
      previous.println();
      previous.flush();
      reset();
      return;
    }
    reset();
  }

  private static void reset() {
    active = false;
    activeOut = null;
    lastLength = 0;
  }

  static int visibleLength(String text) {
    int length = 0;
    for (int index = 0; index < text.length(); index++) {
      char current = text.charAt(index);
      if (current == '\u001B' && index + 1 < text.length() && text.charAt(index + 1) == '[') {
        index += 2;
        while (index < text.length() && !isAnsiFinalByte(text.charAt(index))) {
          index++;
        }
        continue;
      }
      length++;
    }
    return length;
  }

  private static boolean isAnsiFinalByte(char current) {
    return current >= '@' && current <= '~';
  }

  private static void clearLine(PrintStream stream) {
    if (lastLength > 0) {
      stream.print(Const.Symbols.SPACE.repeat(lastLength));
      stream.print('\r');
    }
  }

  public static final class StatusSession implements AutoCloseable {

    private final PrintStream out;
    private final boolean exclusive;
    private boolean closed;

    private StatusSession(PrintStream out, boolean exclusive) {
      this.out = out;
      this.exclusive = exclusive;
    }

    private static void closeStatusSession(PrintStream stream, boolean exclusive) {
      synchronized (LOCK) {
        decrementSession(statusSessions, stream);
        if (exclusive) {
          decrementSession(exclusiveStatusSessions, stream);
        }
      }
    }

    private static void decrementSession(Map<PrintStream, Integer> sessions, PrintStream stream) {
      Integer count = sessions.get(stream);
      if (count == null) {
        return;
      }
      if (count == 1) {
        sessions.remove(stream);
      } else {
        sessions.put(stream, count - 1);
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      closeStatusSession(out, exclusive);
    }
  }
}
