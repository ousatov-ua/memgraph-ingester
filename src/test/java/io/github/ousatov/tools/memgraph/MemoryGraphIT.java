package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ousatov.tools.memgraph.extension.MemgraphExtension;
import io.github.ousatov.tools.memgraph.extension.MemgraphInstance;
import io.github.ousatov.tools.memgraph.schema.Memgraph;
import java.nio.file.Path;
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
 * Integration tests for the project-scoped Memory graph conventions.
 *
 * <p>Memory writes are authored by agents or clients, so these tests verify the schema and graph
 * shape directly against Memgraph.
 *
 * @author Oleksii Usatov
 */
@ExtendWith(MemgraphExtension.class)
class MemoryGraphIT {

  private static final String PROJECT =
      "test-memory-" + UUID.randomUUID().toString().substring(0, 8);
  private static final Path SRC_ROOT = Path.of("/tmp/test-memory/src");
  private static final Path TEST_FILE = Path.of("/tmp/test-memory/src/com/example/Widget.java");

  private static Driver driver;
  private Session session;
  private GraphWriter writer;

  @BeforeAll
  static void setupDriver(MemgraphInstance mg) {
    driver = GraphDatabase.driver(mg.getBoltUrl(), AuthTokens.basic("", ""));
    try (Session s = driver.session()) {
      Memgraph.applySchema(s);
    }
  }

  @AfterAll
  static void tearDownDriver() {
    driver.close();
  }

  @BeforeEach
  void openSession() {
    session = driver.session();
    writer = new GraphWriter(session, PROJECT);
    writer.upsertProject(SRC_ROOT);
  }

  @AfterEach
  void closeAndWipe() {
    session.run("MATCH (n) WHERE n.project = $p DETACH DELETE n", Map.of("p", PROJECT)).consume();
    session.run("MATCH (p:Project {name: $p}) DETACH DELETE p", Map.of("p", PROJECT)).consume();
    session.close();
  }

  @Test
  void memoryItemsCanBeStoredUnderProjectMemoryAnchor() {
    session
        .run(
            "MATCH (m:Memory {project: $p})"
                + " MERGE (d:Decision {id: 'DEC-test-memory-anchor', project: $p})"
                + " SET d.title = 'Use memory graph',"
                + "     d.status = 'accepted',"
                + "     d.rationale = 'Durable project context belongs in Memgraph'"
                + " MERGE (m)-[:HAS_DECISION]->(d)",
            Map.of("p", PROJECT))
        .consume();

    long count =
        session
            .run(
                "MATCH (:Project {name: $p})-[:HAS_MEMORY]->(:Memory {project: $p})"
                    + "-[:HAS_DECISION]->(:Decision {id: 'DEC-test-memory-anchor', project: $p})"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);
  }

  @Test
  void memoryItemsCanReferToCodeRefsResolvedToCodeNodes() {
    writer.upsertFile(TEST_FILE);

    session
        .run(
            "MATCH (m:Memory {project: $p})"
                + " MERGE (d:Decision {id: 'DEC-test-refers-to-file', project: $p})"
                + " SET d.title = 'Document file-specific decision', d.status = 'accepted'"
                + " MERGE (ref:CodeRef {project: $p, targetType: 'File', key: $path})"
                + " MERGE (m)-[:HAS_DECISION]->(d)"
                + " MERGE (d)-[:REFERS_TO]->(ref)",
            Map.of("p", PROJECT, "path", TEST_FILE.toString()))
        .consume();

    writer.resolveCodeRefs();

    long count =
        session
            .run(
                "MATCH (:Memory {project: $p})-[:HAS_DECISION]->"
                    + "(:Decision {id: 'DEC-test-refers-to-file', project: $p})"
                    + "-[:REFERS_TO]->(:CodeRef {project: $p, targetType: 'File', key: $path})"
                    + "-[:RESOLVES_TO]->(:File {path: $path, project: $p})"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT, "path", TEST_FILE.toString()))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);
  }

  @Test
  @SuppressWarnings("java:S5778")
  void memoryIdentityConstraintRejectsDuplicateDecisionIdsWithinProject() {
    session
        .run(
            "CREATE (:Decision {id: 'DEC-test-duplicate', project: $p, status: 'accepted'})",
            Map.of("p", PROJECT))
        .consume();

    assertThrows(
        RuntimeException.class,
        () ->
            session
                .run(
                    "CREATE (:Decision {id: 'DEC-test-duplicate',"
                        + " project: $p, status: 'accepted'})",
                    Map.of("p", PROJECT))
                .consume());
  }

  @Test
  @SuppressWarnings("java:S5778")
  void codeRefIdentityConstraintRejectsDuplicateTargetsWithinProject() {
    session
        .run(
            "CREATE (:CodeRef {project: $p, targetType: 'Class', key: 'com.example.Widget'})",
            Map.of("p", PROJECT))
        .consume();

    assertThrows(
        RuntimeException.class,
        () ->
            session
                .run(
                    "CREATE (:CodeRef {project: $p,"
                        + " targetType: 'Class', key: 'com.example.Widget'})",
                    Map.of("p", PROJECT))
                .consume());
  }

  @Test
  void wipeMemoriesDeletesProjectMemoryGraphAndPreservesCodeGraph() {
    writer.upsertFile(TEST_FILE);
    session
        .run(
            "MATCH (m:Memory {project: $p})"
                + " MERGE (d:Decision {id: 'DEC-test-wipe-memories', project: $p})"
                + " SET d.status = 'accepted'"
                + " MERGE (ref:CodeRef {project: $p, targetType: 'File', key: $path})"
                + " MERGE (m)-[:HAS_DECISION]->(d)"
                + " MERGE (d)-[:REFERS_TO]->(ref)",
            Map.of("p", PROJECT, "path", TEST_FILE.toString()))
        .consume();

    writer.wipeMemories();

    long memoryCount =
        session
            .run(
                "MATCH (n) WHERE n.project = $p"
                    + " AND (n:Memory OR n:Decision OR n:Idea OR n:Context OR n:Rule"
                    + " OR n:Task OR n:Finding OR n:Question OR n:Risk OR n:ADR"
                    + " OR n:CodeRef)"
                    + " RETURN count(n) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();
    assertEquals(0, memoryCount);

    long fileCount =
        session
            .run(
                "MATCH (:Project {name: $p})-[:CONTAINS]->(:Code {project: $p})"
                    + "-[:CONTAINS]->(:File {path: $path, project: $p})"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT, "path", TEST_FILE.toString()))
            .single()
            .get("n")
            .asLong();
    assertEquals(1, fileCount);

    writer.upsertProject(SRC_ROOT);

    long emptyMemoryRootCount =
        session
            .run(
                "MATCH (:Project {name: $p})-[:HAS_MEMORY]->(:Memory {project: $p})"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();
    assertEquals(1, emptyMemoryRootCount);
  }
}
