package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.def.Const;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests for CypherResource class.
 *
 * @author Oleksii Usatov
 */
class CypherResourceTest {

  private static String resource(String file) {
    String path = "/io/github/ousatov/tools/memgraph/cypher/" + file;
    try (InputStream in = CypherResourceTest.class.getResourceAsStream(path)) {
      assertNotNull(in, path + " must be present");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError(path + " could not be loaded", e);
    }
  }

  @Test
  void createSchemaContainsMemoryConstraints() {
    String schema = resource("create-schema.cypher");

    assertTrue(schema.contains("CREATE CONSTRAINT ON (m:Memory)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (d:Decision)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (i:Idea)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (c:Context)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (r:Rule)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (t:Task)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (f:Finding)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (q:Question)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (risk:Risk)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (adr:ADR)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (ref:CodeRef)"));
  }

  @Test
  void createSchemaContainsMemoryIndexes() {
    String schema = resource("create-schema.cypher");

    assertTrue(schema.contains("CREATE INDEX ON :Memory(project)"));
    assertTrue(schema.contains("CREATE INDEX ON :Decision(status)"));
    assertTrue(schema.contains("CREATE INDEX ON :Decision(topic)"));
    assertTrue(schema.contains("CREATE INDEX ON :Idea(status)"));
    assertTrue(schema.contains("CREATE INDEX ON :Context(updatedAt)"));
    assertTrue(schema.contains("CREATE INDEX ON :Rule(severity)"));
    assertTrue(schema.contains("CREATE INDEX ON :Task(priority)"));
    assertTrue(schema.contains("CREATE INDEX ON :Finding(type)"));
    assertTrue(schema.contains("CREATE INDEX ON :Question(status)"));
    assertTrue(schema.contains("CREATE INDEX ON :Risk(status)"));
    assertTrue(schema.contains("CREATE INDEX ON :ADR(status)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeRef(targetType)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeRef(key)"));
  }

  @Test
  void wipeProjectCodeResourcePreservesMemoryLabels() {
    String cypher = Const.Cypher.CYPHER_WIPE_PROJECT_CODE;

    assertTrue(cypher.contains("n:Code"));
    assertTrue(cypher.contains("n:Package"));
    assertTrue(cypher.contains("n:File"));
    assertTrue(cypher.contains("n:Class"));
    assertTrue(cypher.contains("n:Interface"));
    assertTrue(cypher.contains("n:Annotation"));
    assertTrue(cypher.contains("n:Method"));
    assertTrue(cypher.contains("n:Field"));
    assertFalse(cypher.contains("n:Memory"));
    assertFalse(cypher.contains("n:Decision"));
    assertFalse(cypher.contains("n:CodeRef"));
  }

  @Test
  void wipeProjectMemoriesResourcePreservesCodeLabels() {
    String cypher = Const.Cypher.CYPHER_WIPE_PROJECT_MEMORIES;

    assertTrue(cypher.contains("n:Memory"));
    assertTrue(cypher.contains("n:Decision"));
    assertTrue(cypher.contains("n:Idea"));
    assertTrue(cypher.contains("n:Context"));
    assertTrue(cypher.contains("n:Rule"));
    assertTrue(cypher.contains("n:Task"));
    assertTrue(cypher.contains("n:Finding"));
    assertTrue(cypher.contains("n:Question"));
    assertTrue(cypher.contains("n:Risk"));
    assertTrue(cypher.contains("n:ADR"));
    assertTrue(cypher.contains("n:CodeRef"));
    assertFalse(cypher.matches("(?s).*\\bn:Code(?!Ref)\\b.*"));
    assertFalse(cypher.contains("n:Class"));
  }
}
