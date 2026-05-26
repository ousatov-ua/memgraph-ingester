package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Invokes the bundled Python AST helper and converts its NDJSON output into neutral records.
 *
 * @author Oleksii Usatov
 */
public final class PythonAnalyzer {

  private static final Logger log = LoggerFactory.getLogger(PythonAnalyzer.class);
  private static final String HELPER_RESOURCE_DIR = "/io/github/ousatov/tools/memgraph/python/";
  private static final String HELPER_SCRIPT_NAME = "python-analyzer.py";
  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);

  private final Path sourceRoot;
  private final ManagedPythonRuntime pythonRuntime;
  private final Path helperScript;

  public PythonAnalyzer(Path sourceRoot) {
    this(
        sourceRoot,
        new ManagedPythonRuntime(
            ManagedPythonRuntime.defaultCacheRoot(),
            ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
            ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
            RuntimeMode.MANAGED));
  }

  public PythonAnalyzer(Path sourceRoot, ManagedPythonRuntime pythonRuntime) {
    this(sourceRoot, pythonRuntime, extractHelperScript());
  }

  private PythonAnalyzer(Path sourceRoot, ManagedPythonRuntime pythonRuntime, Path helperScript) {
    this.sourceRoot = sourceRoot;
    this.pythonRuntime = pythonRuntime;
    this.helperScript = helperScript;
  }

  private static CompletableFuture<String> readAsync(InputStream input, String streamName) {
    CompletableFuture<String> output = new CompletableFuture<>();
    Thread reader =
        new Thread(
            () -> {
              try (input) {
                output.complete(new String(input.readAllBytes(), StandardCharsets.UTF_8));
              } catch (IOException e) {
                output.completeExceptionally(e);
              }
            },
            "memgraph-python-analyzer-" + streamName);
    reader.setDaemon(true);
    reader.start();
    return output;
  }

  private static String outputOf(CompletableFuture<String> output, Path file, String streamName) {
    try {
      return output.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while reading analyzer " + streamName, e);
    } catch (ExecutionException e) {
      throw new ProcessingException(
          "Could not read Python analyzer " + streamName + " for " + file, e.getCause());
    }
  }

  private static PythonAnalysis parse(String stdout, Path file) {
    String moduleFqn = null;
    String moduleName = null;
    String packageName = null;
    String modulePath = null;
    int startLine = 1;
    int endLine = 1;
    List<PythonAnalysis.TypeDecl> types = new ArrayList<>();
    List<PythonAnalysis.RelationDecl> relations = new ArrayList<>();
    List<PythonAnalysis.MemberDecl> members = new ArrayList<>();
    List<PythonAnalysis.AnnotationDecl> annotations = new ArrayList<>();
    List<PythonAnalysis.CallDecl> calls = new ArrayList<>();

    for (String line : stdout.lines().filter(l -> !l.isBlank()).toList()) {
      Map<String, String> obj = parseObject(line);
      switch (value(obj, Params.RECORD)) {
        case Params.MODULE -> {
          moduleFqn = value(obj, Params.MODULE_FQN);
          moduleName = value(obj, Params.MODULE_NAME);
          packageName = value(obj, Params.PACKAGE_NAME);
          modulePath = value(obj, Params.MODULE_PATH);
          startLine = intValue(obj, Params.START_LINE);
          endLine = intValue(obj, Params.END_LINE);
        }
        case Params.TYPE ->
            types.add(
                new PythonAnalysis.TypeDecl(
                    value(obj, Params.KIND),
                    value(obj, Params.FQN),
                    value(obj, Params.NAME),
                    value(obj, Params.FRAMEWORK),
                    booleanValue(obj, Params.HAS_CONSTRUCTOR),
                    booleanValue(obj, Params.IS_ABSTRACT),
                    intValue(obj, Params.START_LINE),
                    intValue(obj, Params.END_LINE)));
        case Params.RELATION ->
            relations.add(
                new PythonAnalysis.RelationDecl(
                    value(obj, Params.KIND),
                    value(obj, Params.CHILD_FQN),
                    value(obj, Params.TARGET_FQN)));
        case Params.MEMBER ->
            members.add(
                new PythonAnalysis.MemberDecl(
                    value(obj, Params.OWNER_FQN),
                    value(obj, Params.MEMBER_TYPE),
                    value(obj, Params.KIND),
                    value(obj, Params.KEY),
                    value(obj, Params.NAME),
                    value(obj, Params.DATA_TYPE),
                    booleanValue(obj, Params.IS_STATIC),
                    intValue(obj, Params.START_LINE),
                    intValue(obj, Params.END_LINE)));
        case Params.ANNOTATION ->
            annotations.add(
                new PythonAnalysis.AnnotationDecl(
                    value(obj, Params.OWNER_KIND),
                    value(obj, Params.OWNER_KEY),
                    value(obj, Params.FQN),
                    value(obj, Params.NAME)));
        case Params.CALL, Params.CALL_BY_NAME ->
            calls.add(
                new PythonAnalysis.CallDecl(
                    value(obj, Params.CALLER_SIGNATURE),
                    value(obj, Params.CALLEE_SIGNATURE),
                    value(obj, Params.CALLEE_OWNER_FQN),
                    value(obj, Params.CALLEE_NAME)));
        default -> log.debug("Ignoring unknown Python analyzer record: {}", line);
      }
    }
    if (moduleFqn == null) {
      throw new ProcessingException("Python analyzer produced no module record for " + file);
    }
    return new PythonAnalysis(
        moduleFqn,
        moduleName,
        packageName,
        modulePath,
        startLine,
        endLine,
        List.copyOf(types),
        List.copyOf(relations),
        List.copyOf(members),
        List.copyOf(annotations),
        List.copyOf(calls));
  }

  private static String value(Map<String, String> obj, String key) {
    return obj.getOrDefault(key, "");
  }

  private static int intValue(Map<String, String> obj, String key) {
    String value = value(obj, key);
    return value.isBlank() ? 0 : Integer.parseInt(value);
  }

  private static boolean booleanValue(Map<String, String> obj, String key) {
    return Boolean.parseBoolean(value(obj, key));
  }

  private static Map<String, String> parseObject(String line) {
    JsonCursor cursor = new JsonCursor(line);
    return cursor.object();
  }

  private static Path extractHelperScript() {
    String resourcePath = HELPER_RESOURCE_DIR + HELPER_SCRIPT_NAME;
    try (InputStream in = PythonAnalyzer.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new ProcessingException(resourcePath + " is missing from jar");
      }
      Path helperDir = Files.createTempDirectory("memgraph-ingester-python-analyzer-");
      helperDir.toFile().deleteOnExit();
      Path helper = helperDir.resolve(HELPER_SCRIPT_NAME);
      Files.write(helper, in.readAllBytes());
      helper.toFile().deleteOnExit();
      return helper;
    } catch (IOException e) {
      throw new ProcessingException("Could not extract Python analyzer helper", e);
    }
  }

  public PythonAnalysis analyze(Path file) {
    Path python = pythonRuntime.pythonExecutable();
    ProcessBuilder processBuilder =
        new ProcessBuilder(
            python.toString(),
            helperScript.toString(),
            "--file",
            file.toString(),
            "--root",
            sourceRoot.toString());
    try {
      Process process = processBuilder.start();
      CompletableFuture<String> stdout = readAsync(process.getInputStream(), "stdout");
      CompletableFuture<String> stderr = readAsync(process.getErrorStream(), "stderr");
      if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new ProcessingException("Python analyzer timed out for " + file);
      }
      String stderrText = outputOf(stderr, file, "stderr");
      if (process.exitValue() != 0) {
        throw new ProcessingException("Python analyzer failed for " + file + ": " + stderrText);
      }
      return parse(outputOf(stdout, file, "stdout"), file);
    } catch (IOException e) {
      throw new ProcessingException("Could not run Python analyzer for " + file, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while analyzing " + file, e);
    }
  }

  public PythonAnalyzer withSourceRoot(Path sourceRoot) {
    return new PythonAnalyzer(sourceRoot, pythonRuntime, helperScript);
  }

  private static final class JsonCursor {

    private final String input;
    private int pos;

    private JsonCursor(String input) {
      this.input = input;
    }

    Map<String, String> object() {
      Map<String, String> result = new HashMap<>();
      skipWhitespace();
      expect('{');
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        return result;
      }
      while (true) {
        String key = string();
        skipWhitespace();
        expect(':');
        skipWhitespace();
        result.put(key, primitiveValue());
        skipWhitespace();
        char next = peek();
        if (next == ',') {
          pos++;
          skipWhitespace();
        } else if (next == '}') {
          pos++;
          return result;
        } else {
          throw error("Expected ',' or '}'");
        }
      }
    }

    private String primitiveValue() {
      if (peek() == '"') {
        return string();
      }
      int start = pos;
      while (pos < input.length()) {
        char current = input.charAt(pos);
        if (current == ',' || current == '}') {
          break;
        }
        pos++;
      }
      return input.substring(start, pos).trim();
    }

    private String string() {
      expect('"');
      StringBuilder out = new StringBuilder();
      while (pos < input.length()) {
        char current = input.charAt(pos++);
        if (current == '"') {
          return out.toString();
        }
        if (current == '\\') {
          out.append(escape());
        } else {
          out.append(current);
        }
      }
      throw error("Unterminated string");
    }

    private char escape() {
      if (pos >= input.length()) {
        throw error("Unterminated escape");
      }
      char escaped = input.charAt(pos++);
      return switch (escaped) {
        case '"', '\\', '/' -> escaped;
        case 'b' -> '\b';
        case 'f' -> '\f';
        case 'n' -> '\n';
        case 'r' -> '\r';
        case 't' -> '\t';
        case 'u' -> unicodeEscape();
        default -> throw error("Unsupported escape: " + escaped);
      };
    }

    private char unicodeEscape() {
      if (pos + 4 > input.length()) {
        throw error("Incomplete unicode escape");
      }
      String hex = input.substring(pos, pos + 4);
      try {
        int value = Integer.parseInt(hex, 16);
        pos += 4;
        return (char) value;
      } catch (NumberFormatException e) {
        throw error("Invalid unicode escape", e);
      }
    }

    private void skipWhitespace() {
      while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
        pos++;
      }
    }

    private char peek() {
      if (pos >= input.length()) {
        throw error("Unexpected end of input");
      }
      return input.charAt(pos);
    }

    private void expect(char expected) {
      if (peek() != expected) {
        throw error("Expected '" + expected + "'");
      }
      pos++;
    }

    private ProcessingException error(String message) {
      return new ProcessingException(message + " while parsing Python analyzer output: " + input);
    }

    private ProcessingException error(String message, Throwable cause) {
      return new ProcessingException(
          message + " while parsing Python analyzer output: " + input, cause);
    }
  }
}
