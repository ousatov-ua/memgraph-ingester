package io.github.ousatov.tools.memgraph.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Loads configurable application defaults from {@code memgraph-ingester.yml}. */
public final class AppConfig {

  private static final String RESOURCE = "memgraph-ingester.yml";
  private static final String CONFIG_PATH_PROPERTY = "memgraph.ingester.config";
  private static final String CONFIG_PATH_ENV = "MEMGRAPH_INGESTER_CONFIG";
  private static final Map<String, String> VALUES = load();

  private AppConfig() {}

  public static String stringValue(String key, String fallback) {
    String value = VALUES.get(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  public static String stringValue(String key) {
    String value = VALUES.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing config value: " + key);
    }
    return value;
  }

  public static int intValue(String key, int fallback) {
    String value = VALUES.get(key);
    return value == null || value.isBlank() ? fallback : parseInt(key, value);
  }

  public static int intValue(String key) {
    return parseInt(key, stringValue(key));
  }

  public static long longValue(String key, long fallback) {
    String value = VALUES.get(key);
    return value == null || value.isBlank() ? fallback : parseLong(key, value);
  }

  public static long longValue(String key) {
    return parseLong(key, stringValue(key));
  }

  public static Duration durationValue(String key, Duration fallback) {
    String value = VALUES.get(key);
    return value == null || value.isBlank() ? fallback : parseDuration(key, value);
  }

  public static Duration durationValue(String key) {
    return parseDuration(key, stringValue(key));
  }

  private static Map<String, String> load() {
    Map<String, String> values = new HashMap<>();
    loadResource(values);
    loadExternal(values);
    return Map.copyOf(values);
  }

  private static void loadResource(Map<String, String> values) {
    try (var input = AppConfig.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (input == null) {
        return;
      }
      try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
        parse(reader, values);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Could not read " + RESOURCE, e);
    }
  }

  private static void loadExternal(Map<String, String> values) {
    String configured = System.getProperty(CONFIG_PATH_PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(CONFIG_PATH_ENV);
    }
    if (configured == null || configured.isBlank()) {
      return;
    }
    Path path = Path.of(configured).toAbsolutePath().normalize();
    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      parse(reader, values);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read config file " + path, e);
    }
  }

  private static void parse(BufferedReader reader, Map<String, String> values) throws IOException {
    ArrayDeque<String> path = new ArrayDeque<>();
    String line;
    while ((line = reader.readLine()) != null) {
      String trimmed = stripComment(line).trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int indent = countLeadingSpaces(line);
      if (indent % 2 != 0) {
        throw new IllegalStateException("YAML indentation must use multiples of two spaces");
      }
      int depth = indent / 2;
      while (path.size() > depth) {
        path.removeLast();
      }
      int colon = trimmed.indexOf(':');
      if (colon < 1) {
        throw new IllegalStateException("Invalid YAML line: " + line);
      }
      String key = trimmed.substring(0, colon).trim();
      String value = trimmed.substring(colon + 1).trim();
      if (value.isEmpty()) {
        path.addLast(key);
        continue;
      }
      values.put(dotted(path, key), unquote(value));
    }
  }

  private static String dotted(ArrayDeque<String> path, String key) {
    if (path.isEmpty()) {
      return key;
    }
    return String.join(".", path) + "." + key;
  }

  private static String stripComment(String line) {
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        quoted = !quoted;
      } else if (!quoted && ch == '#') {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static int countLeadingSpaces(String line) {
    int count = 0;
    while (count < line.length() && line.charAt(count) == ' ') {
      count++;
    }
    return count;
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static int parseInt(String key, String value) {
    try {
      return Integer.parseInt(value.replace("_", ""));
    } catch (NumberFormatException e) {
      throw new IllegalStateException(key + " must be an integer: " + value, e);
    }
  }

  private static long parseLong(String key, String value) {
    try {
      return Long.parseLong(value.replace("_", ""));
    } catch (NumberFormatException e) {
      throw new IllegalStateException(key + " must be a long: " + value, e);
    }
  }

  private static Duration parseDuration(String key, String value) {
    String normalized = value.trim().toLowerCase();
    try {
      if (normalized.startsWith("pt")) {
        return Duration.parse(value);
      }
      if (normalized.endsWith("ms")) {
        return Duration.ofMillis(parseLong(key, normalized.substring(0, normalized.length() - 2)));
      }
      if (normalized.endsWith("s")) {
        return Duration.ofSeconds(parseLong(key, normalized.substring(0, normalized.length() - 1)));
      }
      if (normalized.endsWith("m")) {
        return Duration.ofMinutes(parseLong(key, normalized.substring(0, normalized.length() - 1)));
      }
      if (normalized.endsWith("h")) {
        return Duration.ofHours(parseLong(key, normalized.substring(0, normalized.length() - 1)));
      }
      return Duration.ofMillis(parseLong(key, normalized));
    } catch (RuntimeException e) {
      throw new IllegalStateException(key + " must be a duration: " + value, e);
    }
  }
}
