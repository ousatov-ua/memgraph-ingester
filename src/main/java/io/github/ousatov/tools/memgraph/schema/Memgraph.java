package io.github.ousatov.tools.memgraph.schema;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

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

  private static List<String> migrateSchemaQueries() {
    return List.of(
        cypher("migrate-schema-legacy-constraints.cypher"),
        cypher("Js/migrate-schema.cypher"),
        cypher("Python/migrate-schema.cypher"),
        cypher("Java/migrate-schema.cypher"),
        cypher("migrate-schema-cleanup.cypher"));
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
    applyTo(cypher, session, false);
  }

  private static void applyTo(String cypher, Session session, boolean ignoreMissingSchemaDrops) {
    for (String stmt : splitStatements(cypher)) {
      try {
        session.run(stmt).consume();
      } catch (RuntimeException e) {
        if (!ignoreMissingSchemaDrops || !isMissingSchemaDrop(stmt, e)) {
          throw e;
        }
      }
    }
  }

  static boolean isMissingSchemaDrop(String stmt, RuntimeException e) {
    String normalizedStmt = stmt.stripLeading().toUpperCase(Locale.ROOT);
    if (!normalizedStmt.startsWith("DROP INDEX") && !normalizedStmt.startsWith("DROP CONSTRAINT")) {
      return false;
    }
    String message = e.getMessage();
    if (message == null) {
      return false;
    }
    String normalizedMessage = message.toLowerCase(Locale.ROOT);
    return normalizedMessage.contains("doesn't exist")
        || normalizedMessage.contains("does not exist")
        || normalizedMessage.contains("not found")
        || normalizedMessage.contains("no such");
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
    applyTo(dropSchemaQuery(), session, true);
    applyTo(wipeAllDataQuery(), session);
  }

  /**
   * Applies our schema to the database.
   *
   * @param session session to use
   */
  public static void applySchema(Session session) {
    migrateSchemaQueries().forEach(query -> applyTo(query, session, true));
    applyTo(createSchemaQuery(), session);
  }

  /**
   * Returns whether the active schema has language-scoped code and package uniqueness.
   *
   * @param session session to use
   * @return true when the language-aware schema constraints are present
   */
  public static boolean hasLanguageScopedCodeSchema(Session session) {
    boolean languageConstraint = false;
    boolean codeConstraint = false;
    boolean packageConstraint = false;
    Result result = session.run("SHOW CONSTRAINT INFO");
    while (result.hasNext()) {
      var theRecord = result.next();
      if (!"unique".equals(theRecord.get("constraint type").asString(""))) {
        continue;
      }
      String label = theRecord.get("label").asString("");
      List<String> properties = theRecord.get("properties").asList(Value::asString);
      if ("Language".equals(label) && hasSameProperties(properties, Labels.PROJECT, "name")) {
        languageConstraint = true;
      } else if ("Code".equals(label)
          && hasSameProperties(properties, Labels.PROJECT, "language")) {
        codeConstraint = true;
      } else if ("Package".equals(label)
          && hasSameProperties(properties, Labels.PROJECT, "name", "language")) {
        packageConstraint = true;
      }
    }
    return languageConstraint && codeConstraint && packageConstraint;
  }

  static boolean hasSameProperties(List<String> actual, String... expected) {
    return actual.size() == expected.length && Set.copyOf(actual).equals(Set.of(expected));
  }
}
