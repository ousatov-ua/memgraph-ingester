package io.github.ousatov.tools.memgraph.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Memgraph}.
 *
 * @author Oleksii Usatov
 */
class MemgraphTest {

  @Test
  void identifiesMissingSchemaDropErrors() {
    assertTrue(
        Memgraph.isMissingSchemaDrop(
            "DROP INDEX ON :Method(ownerFqn)",
            new RuntimeException("Index on label Method on properties ownerFqn doesn't exist.")));

    assertTrue(
        Memgraph.isMissingSchemaDrop(
            "DROP CONSTRAINT ON (m:Method) ASSERT m.signature, m.project IS UNIQUE",
            new RuntimeException("Constraint UNIQUE on label Method does not exist.")));
  }

  @Test
  void doesNotIgnoreNonDropOrUnrelatedErrors() {
    assertFalse(
        Memgraph.isMissingSchemaDrop(
            "CREATE INDEX ON :Method(ownerFqn)",
            new RuntimeException("Index on label Method on properties ownerFqn doesn't exist.")));

    assertFalse(
        Memgraph.isMissingSchemaDrop(
            "DROP INDEX ON :Method(ownerFqn)", new RuntimeException("Syntax error in Cypher")));
  }

  @Test
  void doesNotIgnoreDropErrorsWithoutMessage() {
    assertFalse(
        Memgraph.isMissingSchemaDrop(
            "DROP INDEX ON :Method(ownerFqn)", new RuntimeException((String) null)));
  }

  @Test
  void identifiesAlternativeMissingSchemaMessages() {
    assertTrue(
        Memgraph.isMissingSchemaDrop(
            "DROP INDEX ON :Method(ownerFqn)", new RuntimeException("index not found")));

    assertTrue(
        Memgraph.isMissingSchemaDrop(
            "DROP CONSTRAINT ON (m:Method) ASSERT m.signature IS UNIQUE",
            new RuntimeException("no such constraint")));
  }

  @Test
  void splitStatementsStripsCommentsAndBlankStatements() {
    List<String> statements =
        Memgraph.splitStatements(
            """
            // schema header
            CREATE INDEX ON :Method(ownerFqn);

            // next statement
            CREATE CONSTRAINT ON (m:Method) ASSERT m.signature IS UNIQUE;
            """);

    assertTrue(statements.contains("CREATE INDEX ON :Method(ownerFqn)"));
    assertTrue(statements.contains("CREATE CONSTRAINT ON (m:Method) ASSERT m.signature IS UNIQUE"));
  }

  @Test
  void constraintPropertyMatchingIgnoresOrder() {
    assertTrue(
        Memgraph.hasSameProperties(
            List.of("project", "name", "language"), "name", "project", "language"));
    assertFalse(Memgraph.hasSameProperties(List.of("project", "name"), "project", "language"));
  }
}
