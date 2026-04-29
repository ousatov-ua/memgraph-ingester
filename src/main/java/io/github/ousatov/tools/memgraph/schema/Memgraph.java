package io.github.ousatov.tools.memgraph.schema;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.neo4j.driver.Session;

/**
 * Memgraph
 *
 * <p>Provides utilities for working with Memgraph.
 *
 * @author Oleksii Usatov
 * @since 21.04.2026
 */
public final class Memgraph {

  private Memgraph() {

    // Empty
  }

  private static String wipeAllDataQuery() {
    return cypher("wipe-all-data.cypher");
  }

  private static String dropSchemaQuery() {
    return cypher("drop-schema.cypher");
  }

  private static String createSchemaQuery() {
    return cypher("create-schema.cypher");
  }

  private static String cypher(String file) {
    String resource = "/io/github/ousatov/tools/memgraph/cypher/" + file;
    try (InputStream in = Memgraph.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new ProcessingException(resource + " is missing from jar");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ProcessingException(resource + " could not be loaded from jar", e);
    }
  }

  private static void applyTo(String cypher, Session session) {
    for (String stmt : splitStatements(cypher)) {
      session.run(stmt);
    }
  }

  /**
   * Splits a multi-statement Cypher file on semicolons, strips comment lines, and returns only
   * non-blank statements.
   */
  static List<String> splitStatements(String cypher) {
    List<String> result = new ArrayList<>();
    for (String raw : cypher.split(";[ \\t]*(?:\\r?\\n|$)")) {
      StringBuilder cleaned = new StringBuilder();
      for (String line : raw.split("\\r?\\n")) {
        if (!line.stripLeading().startsWith("//")) {
          if (!cleaned.isEmpty()) {
            cleaned.append('\n');
          }
          cleaned.append(line);
        }
      }
      if (!cleaned.isEmpty() && !cleaned.toString().isBlank()) {
        result.add(cleaned.toString().strip());
      }
    }
    return result;
  }

  /**
   * Wipes all data from the database. We drop our schema first, then wipe all data.
   *
   * @param session session to use
   */
  public static void wipeAllData(Session session) {
    applyTo(dropSchemaQuery(), session);
    applyTo(wipeAllDataQuery(), session);
  }

  /**
   * Applies our schema to the database.
   *
   * @param session session to use
   */
  public static void applySchema(Session session) {
    applyTo(createSchemaQuery(), session);
  }
}
