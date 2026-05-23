package io.github.ousatov.tools.memgraph.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.extension.MemgraphExtension;
import io.github.ousatov.tools.memgraph.extension.MemgraphInstance;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

/**
 * Integration tests for schema migrations that must preserve existing project code graphs.
 *
 * @author Oleksii Usatov
 */
@ExtendWith(MemgraphExtension.class)
class MemgraphMigrationIT {

  private static final String PROJECT =
      "test-migration-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String SOURCE_ROOT = "/tmp/test-migration/src";
  private static final String JAVA_FILE = SOURCE_ROOT + "/com/example/Widget.java";
  private static final String JS_FILE = SOURCE_ROOT + "/app/widget.ts";

  private static Driver driver;

  @BeforeAll
  static void setupDriver(MemgraphInstance mg) {
    driver = GraphDatabase.driver(mg.getBoltUrl(), AuthTokens.basic("", ""));
  }

  @AfterAll
  static void tearDownDriver() {
    driver.close();
  }

  @BeforeEach
  void resetDatabase() {
    try (Session session = driver.session()) {
      Memgraph.wipeAllData(session);
    }
  }

  @AfterEach
  void wipeProject() {
    try (Session session = driver.session()) {
      session.run("MATCH (n) WHERE n.project = $p DETACH DELETE n", Map.of("p", PROJECT)).consume();
      session.run("MATCH (p:Project {name: $p}) DETACH DELETE p", Map.of("p", PROJECT)).consume();
    }
  }

  @Test
  void applySchemaMigratesLegacyCodeGraphToLanguageRoots() {
    try (Session session = driver.session()) {
      session
          .run(
              "CREATE (project:Project {name: $project})"
                  + " CREATE (legacy:Code {project: $project,"
                  + " sourceRoots: [$sourceRoot], lastIngested: 42})"
                  + " CREATE (project)-[:CONTAINS]->(legacy)"
                  + " CREATE (javaFile:File {project: $project,"
                  + " path: $javaFile, language: 'java'})"
                  + " CREATE (jsFile:File {project: $project,"
                  + " path: $jsFile, language: 'js'})"
                  + " CREATE (javaPkg:Package {project: $project, name: 'com.example'})"
                  + " CREATE (javaJsNamedPkg:Package {project: $project, name: 'js.tools'})"
                  + " CREATE (jsPkg:Package {project: $project, name: 'js.app'})"
                  + " CREATE (javaClass:Class {project: $project,"
                  + " fqn: 'com.example.Widget', name: 'Widget',"
                  + " isExternal: false, language: 'java'})"
                  + " CREATE (jsClass:Class {project: $project,"
                  + " fqn: 'js.app.Widget', name: 'Widget',"
                  + " isExternal: false, language: 'js'})"
                  + " CREATE (legacy)-[:CONTAINS]->(javaFile)"
                  + " CREATE (legacy)-[:CONTAINS]->(jsFile)"
                  + " CREATE (legacy)-[:CONTAINS]->(javaPkg)"
                  + " CREATE (legacy)-[:CONTAINS]->(javaJsNamedPkg)"
                  + " CREATE (legacy)-[:CONTAINS]->(jsPkg)"
                  + " CREATE (javaPkg)-[:CONTAINS]->(javaClass)"
                  + " CREATE (jsPkg)-[:CONTAINS]->(jsClass)",
              Map.of(
                  "project", PROJECT,
                  "sourceRoot", SOURCE_ROOT,
                  "javaFile", JAVA_FILE,
                  "jsFile", JS_FILE))
          .consume();

      Memgraph.applySchema(session);

      assertEquals(1, count(session, languageFileQuery("Java", "java"), "path", JAVA_FILE));
      assertEquals(1, count(session, languageFileQuery("Js", "js"), "path", JS_FILE));
      assertEquals(1, count(session, languagePackageQuery("Java", "java"), "name", "com.example"));
      assertEquals(1, count(session, languagePackageQuery("Java", "java"), "name", "js.tools"));
      assertEquals(1, count(session, languagePackageQuery("Js", "js"), "name", "js.app"));
      assertTrue(Memgraph.hasLanguageScopedCodeSchema(session));
      assertEquals(
          0,
          count(
              session,
              "MATCH (:Project {name: $project})-[:CONTAINS]->(:Code)" + " RETURN count(*) AS n"));
      assertEquals(
          1,
          count(
              session,
              "MATCH (:Language {project: $project, name: 'Java'})"
                  + "-[:CONTAINS]->(code:Code {project: $project, language: 'java'})"
                  + " WHERE $sourceRoot IN code.sourceRoots"
                  + " RETURN count(*) AS n"));
    }
  }

  private static String languageFileQuery(String languageName, String graphName) {
    return "MATCH (:Project {name: $project})-[:CONTAINS]->"
        + "(:Language {name: '"
        + languageName
        + "'})-[:CONTAINS]->"
        + "(:Code {project: $project, language: '"
        + graphName
        + "'})-[:CONTAINS]->(:File {project: $project, path: $path})"
        + " RETURN count(*) AS n";
  }

  private static String languagePackageQuery(String languageName, String graphName) {
    return "MATCH (:Project {name: $project})-[:CONTAINS]->"
        + "(:Language {name: '"
        + languageName
        + "'})-[:CONTAINS]->"
        + "(:Code {project: $project, language: '"
        + graphName
        + "'})-[:CONTAINS]->(:Package {project: $project, name: $name})"
        + " RETURN count(*) AS n";
  }

  private static long count(Session session, String query) {
    return session
        .run(query, Map.of("project", PROJECT, "sourceRoot", SOURCE_ROOT))
        .single()
        .get("n")
        .asLong();
  }

  private static long count(Session session, String query, String param, String value) {
    return session.run(query, Map.of("project", PROJECT, param, value)).single().get("n").asLong();
  }
}
