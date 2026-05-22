package io.github.ousatov.tools.memgraph.exe;

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
 * Invokes the bundled Node.js helper and converts its NDJSON output into neutral records.
 *
 * @author Oleksii Usatov
 */
public final class JsAnalyzer {

  private static final Logger log = LoggerFactory.getLogger(JsAnalyzer.class);
  private static final String HELPER_RESOURCE =
      "/io/github/ousatov/tools/memgraph/js/js-analyzer.cjs";
  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(2);

  private final Path sourceRoot;
  private final ManagedNodeRuntime nodeRuntime;
  private final ManagedTypescriptPackage typescriptPackage;
  private final Path helperScript;

  public JsAnalyzer(
      Path sourceRoot, ManagedNodeRuntime nodeRuntime, ManagedTypescriptPackage typescriptPackage) {
    this.sourceRoot = sourceRoot;
    this.nodeRuntime = nodeRuntime;
    this.typescriptPackage = typescriptPackage;
    this.helperScript = extractHelperScript();
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
            "memgraph-js-analyzer-" + streamName);
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
          "Could not read JavaScript analyzer " + streamName + " for " + file, e.getCause());
    }
  }

  private static JsAnalysis parse(String stdout, Path file) {
    String moduleFqn = null;
    String moduleName = null;
    String packageName = null;
    String modulePath = null;
    int startLine = 1;
    int endLine = 1;
    List<JsAnalysis.TypeDecl> types = new ArrayList<>();
    List<JsAnalysis.MemberDecl> members = new ArrayList<>();
    List<JsAnalysis.AnnotationDecl> annotations = new ArrayList<>();
    List<JsAnalysis.CallDecl> calls = new ArrayList<>();

    for (String line : stdout.lines().filter(l -> !l.isBlank()).toList()) {
      Map<String, String> obj = parseObject(line);
      switch (value(obj, "record")) {
        case "module" -> {
          moduleFqn = value(obj, "moduleFqn");
          moduleName = value(obj, "moduleName");
          packageName = value(obj, "packageName");
          modulePath = value(obj, "modulePath");
          startLine = intValue(obj, Params.START_LINE);
          endLine = intValue(obj, Params.END_LINE);
        }
        case "type" ->
            types.add(
                new JsAnalysis.TypeDecl(
                    value(obj, "kind"),
                    value(obj, "fqn"),
                    value(obj, "name"),
                    value(obj, "framework"),
                    booleanValue(obj, "hasConstructor"),
                    intValue(obj, "startLine"),
                    intValue(obj, "endLine")));
        case "member" ->
            members.add(
                new JsAnalysis.MemberDecl(
                    value(obj, "ownerFqn"),
                    value(obj, "memberType"),
                    value(obj, "kind"),
                    value(obj, "key"),
                    value(obj, "name"),
                    value(obj, "dataType"),
                    booleanValue(obj, "isStatic"),
                    intValue(obj, "startLine"),
                    intValue(obj, "endLine")));
        case "annotation" ->
            annotations.add(
                new JsAnalysis.AnnotationDecl(
                    value(obj, "ownerKind"),
                    value(obj, "ownerKey"),
                    value(obj, "fqn"),
                    value(obj, "name")));
        case "call", "callByName" ->
            calls.add(
                new JsAnalysis.CallDecl(
                    value(obj, "callerSignature"),
                    value(obj, "calleeSignature"),
                    value(obj, "calleeOwnerFqn"),
                    value(obj, "calleeName")));
        default -> log.debug("Ignoring unknown JavaScript analyzer record: {}", line);
      }
    }
    if (moduleFqn == null) {
      throw new ProcessingException("JavaScript analyzer produced no module record for " + file);
    }
    return new JsAnalysis(
        moduleFqn,
        moduleName,
        packageName,
        modulePath,
        startLine,
        endLine,
        List.copyOf(types),
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
    try (InputStream in = JsAnalyzer.class.getResourceAsStream(HELPER_RESOURCE)) {
      if (in == null) {
        throw new ProcessingException(HELPER_RESOURCE + " is missing from jar");
      }
      Path script = Files.createTempFile("memgraph-ingester-js-analyzer-", ".cjs");
      Files.write(script, in.readAllBytes());
      script.toFile().deleteOnExit();
      return script;
    } catch (IOException e) {
      throw new ProcessingException("Could not extract JavaScript analyzer helper", e);
    }
  }

  public JsAnalysis analyze(Path file) {
    Path node = nodeRuntime.nodeExecutable();
    Path nodeModules = typescriptPackage.nodeModulesDir();
    ProcessBuilder processBuilder =
        new ProcessBuilder(
            node.toString(),
            helperScript.toString(),
            "--file",
            file.toString(),
            "--root",
            sourceRoot.toString());
    processBuilder.environment().put("NODE_PATH", nodeModules.toString());
    try {
      Process process = processBuilder.start();
      CompletableFuture<String> stdout = readAsync(process.getInputStream(), "stdout");
      CompletableFuture<String> stderr = readAsync(process.getErrorStream(), "stderr");
      if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new ProcessingException("JavaScript analyzer timed out for " + file);
      }
      String stderrText = outputOf(stderr, file, "stderr");
      if (process.exitValue() != 0) {
        throw new ProcessingException(
            "JavaScript analyzer failed for " + file + ": " + stderrText.trim());
      }
      return parse(outputOf(stdout, file, "stdout"), file);
    } catch (IOException e) {
      throw new ProcessingException("Could not run JavaScript analyzer for " + file, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while analyzing " + file, e);
    }
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
        char c = input.charAt(pos);
        if (c == ',' || c == '}') {
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
        char c = input.charAt(pos++);
        if (c == '"') {
          return out.toString();
        }
        if (c != '\\') {
          out.append(c);
          continue;
        }
        if (pos >= input.length()) {
          throw error("Unterminated escape sequence");
        }
        char escaped = input.charAt(pos++);
        switch (escaped) {
          case '"', '\\', '/' -> out.append(escaped);
          case 'b' -> out.append('\b');
          case 'f' -> out.append('\f');
          case 'n' -> out.append('\n');
          case 'r' -> out.append('\r');
          case 't' -> out.append('\t');
          case 'u' -> out.append(unicodeEscape());
          default -> throw error("Unknown escape sequence");
        }
      }
      throw error("Unterminated string");
    }

    private void skipWhitespace() {
      while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
        pos++;
      }
    }

    private char peek() {
      if (pos >= input.length()) {
        throw error("Unexpected end of JSON");
      }
      return input.charAt(pos);
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

    private void expect(char expected) {
      if (peek() != expected) {
        throw error("Expected '" + expected + "'");
      }
      pos++;
    }

    private ProcessingException error(String message) {
      return new ProcessingException(message + " at position " + pos + " in " + input);
    }

    private ProcessingException error(String message, Throwable cause) {
      return new ProcessingException(message + " at position " + pos + " in " + input, cause);
    }
  }
}
