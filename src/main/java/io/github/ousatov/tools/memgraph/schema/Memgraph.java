package io.github.ousatov.tools.memgraph.schema;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    try (InputStream in =
        Memgraph.class.getResourceAsStream("/io/github/ousatov/tools/memgraph/cypher/" + file)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ProcessingException(file + " is missing from jar", e);
    }
  }

  private static void applyTo(String cypher, Session session) {
    for (String stmt : cypher.split(";\\s*\\n")) {
      if (!stmt.isBlank()) {
        session.run(stmt);
      }
    }
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
