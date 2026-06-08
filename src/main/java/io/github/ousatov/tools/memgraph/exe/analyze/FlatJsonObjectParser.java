package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses the flat JSON object rows emitted by helper analyzers.
 *
 * @author Oleksii Usatov
 */
final class FlatJsonObjectParser {

  private final String input;
  private final ErrorFormatter errorFormatter;
  private int pos;

  private FlatJsonObjectParser(String input, ErrorFormatter errorFormatter) {
    this.input = input;
    this.errorFormatter = errorFormatter;
  }

  static Map<String, String> parse(String input) {
    return new FlatJsonObjectParser(input, FlatJsonObjectParser::positionedError).object();
  }

  static Map<String, String> parsePythonAnalyzerOutput(String input) {
    return new FlatJsonObjectParser(input, FlatJsonObjectParser::pythonAnalyzerError).object();
  }

  private Map<String, String> object() {
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
      throw error("Unterminated escape sequence");
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
      default -> throw error("Unknown escape sequence");
    };
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
      throw error("Expected '" + expected + Const.Symbols.SINGLE_QUOTE);
    }
    pos++;
  }

  private ProcessingException error(String message) {
    return errorFormatter.error(input, pos, message, null);
  }

  private ProcessingException error(String message, Throwable cause) {
    return errorFormatter.error(input, pos, message, cause);
  }

  private static ProcessingException positionedError(
      String input, int position, String message, Throwable cause) {
    String detail = message + " at position " + position + " in " + input;
    return cause == null ? new ProcessingException(detail) : new ProcessingException(detail, cause);
  }

  private static ProcessingException pythonAnalyzerError(
      String input, int position, String message, Throwable cause) {
    String detail =
        message + " at position " + position + " while parsing Python analyzer output: " + input;
    return cause == null ? new ProcessingException(detail) : new ProcessingException(detail, cause);
  }

  @FunctionalInterface
  private interface ErrorFormatter {

    ProcessingException error(String input, int position, String message, Throwable cause);
  }
}
