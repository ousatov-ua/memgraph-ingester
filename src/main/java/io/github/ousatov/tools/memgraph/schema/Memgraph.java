package io.github.ousatov.tools.memgraph.schema;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
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
        if ((!ignoreMissingSchemaDrops || !isMissingSchemaDrop(stmt, e))
            && !isExistingSchemaCreate(stmt, e)) {
          throw e;
        }
      }
    }
  }

  static boolean isMissingSchemaDrop(String stmt, RuntimeException e) {
    String normalizedStmt = stmt.stripLeading().toUpperCase(Locale.ROOT);
    if (!normalizedStmt.startsWith("DROP INDEX")
        && !normalizedStmt.startsWith("DROP CONSTRAINT")
        && !normalizedStmt.startsWith("DROP VECTOR INDEX")) {
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

  static boolean isExistingSchemaCreate(String stmt, RuntimeException e) {
    String normalizedStmt = stmt.stripLeading().toUpperCase(Locale.ROOT);
    if (!normalizedStmt.startsWith("CREATE INDEX")
        && !normalizedStmt.startsWith("CREATE CONSTRAINT")
        && !normalizedStmt.startsWith("CREATE VECTOR INDEX")) {
      return false;
    }
    String message = e.getMessage();
    if (message == null) {
      return false;
    }
    String normalizedMessage = message.toLowerCase(Locale.ROOT);
    return normalizedMessage.contains("already exists")
        || normalizedMessage.contains("exists already")
        || normalizedMessage.contains("equivalent index already exists")
        || normalizedMessage.contains("equivalent constraint already exists");
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
      if (Labels.LANGUAGE.equals(label)
          && hasSameProperties(properties, Labels.PROJECT, Params.NAME)) {
        languageConstraint = true;
      } else if (Labels.CODE.equals(label)
          && hasSameProperties(properties, Labels.PROJECT, Params.LANGUAGE)) {
        codeConstraint = true;
      } else if (Labels.PACKAGE.equals(label)
          && hasSameProperties(properties, Labels.PROJECT, Params.NAME, Params.LANGUAGE)) {
        packageConstraint = true;
      }
    }
    return languageConstraint && codeConstraint && packageConstraint;
  }

  /**
   * Returns whether the active schema contains derived RAG chunk constraints and indexes.
   *
   * @param session session to use
   * @return true when CodeChunk and MemoryChunk uniqueness constraints and property indexes are
   *     present
   */
  public static boolean hasRagChunkSchema(Session session) {
    boolean codeChunkConstraint = false;
    boolean memoryChunkConstraint = false;
    Result result = session.run("SHOW CONSTRAINT INFO");
    while (result.hasNext()) {
      var theRecord = result.next();
      if (!"unique".equals(theRecord.get("constraint type").asString(""))) {
        continue;
      }
      String label = theRecord.get("label").asString("");
      List<String> properties = theRecord.get("properties").asList(Value::asString);
      if (Labels.CODE_CHUNK.equals(label)
          && hasSameProperties(properties, Params.ID, Labels.PROJECT)) {
        codeChunkConstraint = true;
      } else if (Labels.MEMORY_CHUNK.equals(label)
          && hasSameProperties(properties, Params.ID, Labels.PROJECT)) {
        memoryChunkConstraint = true;
      }
    }
    Set<String> indexes = labelPropertyIndexes(session);
    return codeChunkConstraint
        && memoryChunkConstraint
        && indexes.contains(indexKey(Labels.MEMORY_CHUNK, Labels.PROJECT))
        && indexes.contains(indexKey(Labels.MEMORY_CHUNK, Params.SOURCE_LABEL))
        && indexes.contains(indexKey(Labels.MEMORY_CHUNK, Params.SOURCE_ID))
        && indexes.contains(indexKey(Labels.MEMORY_CHUNK, Params.TEXT_HASH))
        && indexes.contains(indexKey(Labels.MEMORY_CHUNK, "embeddingModel"))
        && indexes.contains(indexKey(Labels.MEMORY_CHUNK, "embeddingDimensions"))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, Labels.PROJECT))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, Params.SOURCE_LABEL))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, Params.SOURCE_ID))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, Params.TEXT_HASH))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, "embeddingModel"))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, "embeddingDimensions"))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, Params.LANGUAGE))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, Params.PATH))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, Params.OWNER_FQN))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, "signature"));
  }

  /** Returns whether performance-critical single-property lookup indexes are present. */
  public static boolean hasPerformanceIndexes(Session session) {
    Set<String> indexes = labelPropertyIndexes(session);
    return indexes.contains(indexKey(Labels.FILE, Labels.PROJECT))
        && indexes.contains(indexKey(Labels.FILE, Params.PATH))
        && indexes.contains(indexKey(Labels.FILE, Params.LANGUAGE))
        && indexes.contains(indexKey(Labels.PACKAGE, Labels.PROJECT))
        && indexes.contains(indexKey(Labels.PACKAGE, Params.NAME))
        && indexes.contains(indexKey(Labels.CLASS, Params.FQN))
        && indexes.contains(indexKey(Labels.INTERFACE, Params.FQN))
        && indexes.contains(indexKey(Labels.ANNOTATION, Params.FQN))
        && indexes.contains(indexKey(Labels.METHOD, "signature"))
        && indexes.contains(indexKey(Labels.FIELD, Params.FQN))
        && indexes.contains(indexKey(Labels.PENDING_CALL, Params.CALLER_SIGNATURE))
        && indexes.contains(indexKey(Labels.PENDING_CALL, Params.CALLEE_NAME))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, Params.ID))
        && indexes.contains(indexKey(Labels.CODE_CHUNK, "embeddingDirty"))
        && indexes.contains(indexKey(Labels.MEMORY_CHUNK, Params.ID))
        && indexes.contains(indexKey(Labels.MEMORY_CHUNK, "embeddingDirty"));
  }

  private static Set<String> labelPropertyIndexes(Session session) {
    Set<String> indexes = new HashSet<>();
    Result result = session.run("SHOW INDEX INFO");
    while (result.hasNext()) {
      var record = result.next();
      if (!"label+property".equals(record.get("index type").asString(""))) {
        continue;
      }
      String label = record.get("label").asString("");
      List<String> properties = record.get("property").asList(Value::asString);
      if (properties.size() == 1) {
        indexes.add(indexKey(label, properties.getFirst()));
      }
    }
    return indexes;
  }

  private static String indexKey(String label, String property) {
    return label + "." + property;
  }

  static boolean hasSameProperties(List<String> actual, String... expected) {
    return actual.size() == expected.length && Set.copyOf(actual).equals(Set.of(expected));
  }
}
