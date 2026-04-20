package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.extension.MemgraphExtension;
import io.github.ousatov.tools.memgraph.extension.MemgraphInstance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

/**
 * End-to-end integration tests for {@link IngestionOrchestrator}.
 *
 * <p>Each test uses a unique project name and a freshly created temp source tree so tests are fully
 * independent. Cleanup happens in {@code @AfterEach}.
 *
 * @author Oleksii Usatov
 */
@ExtendWith(MemgraphExtension.class)
class IngestionOrchestratorIT {

  private static final String PROJECT_BASE =
      "test-orch-" + UUID.randomUUID().toString().substring(0, 8);

  private static Driver driver;
  private Path sourceDir;
  private String currentProject;

  @BeforeAll
  static void setupDriver(MemgraphInstance mg) {
    driver = GraphDatabase.driver(mg.getBoltUrl(), AuthTokens.basic("", ""));
  }

  @AfterAll
  static void tearDownDriver() {
    driver.close();
  }

  private static Path buildSampleSourceTree() throws IOException {
    Path dir = Files.createTempDirectory("orch-src-");
    Path pkgDir = dir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Widget.java"),
        """
        package com.example;

        /** A widget. */
        public class Widget {
          private String name;

          /** Returns the name. */
          public String getName() {
            return name;
          }
        }
        """);
    Files.writeString(
        pkgDir.resolve("Describable.java"),
        """
        package com.example;

        /** Something that can describe itself. */
        public interface Describable {
          String describe();
        }
        """);
    return dir;
  }

  private static void wipeProject(String project) {
    try (Session s = driver.session()) {
      s.run("MATCH (n) WHERE n.project = $p DETACH DELETE n", Map.of("p", project)).consume();
      s.run("MATCH (p:Project {name: $p}) DETACH DELETE p", Map.of("p", project)).consume();
    }
  }

  private static void deleteDir(Path dir) throws IOException {
    if (dir != null && Files.exists(dir)) {
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  var _ = p.toFile().delete();
                });
      }
    }
  }

  @AfterEach
  void cleanup() throws IOException {
    if (currentProject != null) {
      wipeProject(currentProject);
    }
    if (sourceDir != null && Files.exists(sourceDir)) {
      try (Stream<Path> walk = Files.walk(sourceDir)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  var _ = p.toFile().delete();
                });
      }
    }
  }

  @Test
  void ingestsSequentiallyWithZeroFailures() throws Exception {
    currentProject = PROJECT_BASE + "-seq";
    sourceDir = buildSampleSourceTree();

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(false);

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(classCount >= 1, "At least one :Class node expected after ingestion");
    }
  }

  @Test
  void ingestsInParallelWithZeroFailures() throws Exception {
    currentProject = PROJECT_BASE + "-par";
    sourceDir = buildSampleSourceTree();

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 4, driver, new ParseService(sourceDir))
            .run(false);

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(classCount >= 1);
    }
  }

  @Test
  void parallelAndSequentialIngestSameNodeCount() throws Exception {
    Path seqDir = buildSampleSourceTree();
    Path parDir = buildSampleSourceTree();
    String seqProject = PROJECT_BASE + "-cmp-seq";
    String parProject = PROJECT_BASE + "-cmp-par";

    try {
      new IngestionOrchestrator(seqDir, seqProject, 1, driver, new ParseService(seqDir)).run(false);
      new IngestionOrchestrator(parDir, parProject, 4, driver, new ParseService(parDir)).run(false);

      try (Session s = driver.session()) {
        long seqClasses =
            s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", seqProject))
                .single()
                .get("n")
                .asLong();
        long parClasses =
            s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", parProject))
                .single()
                .get("n")
                .asLong();
        assertEquals(
            seqClasses, parClasses, "Sequential and parallel must produce equal class count");
      }
    } finally {
      wipeProject(seqProject);
      wipeProject(parProject);
      deleteDir(seqDir);
      deleteDir(parDir);
    }
  }

  @Test
  void wipeDeletesExistingNodesBeforeReingest() throws Exception {
    currentProject = PROJECT_BASE + "-wipe";
    sourceDir = buildSampleSourceTree();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    orchestrator.run(false);
    long countAfterFirst;
    try (Session s = driver.session()) {
      countAfterFirst =
          s.run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
    }

    int failures = orchestrator.run(true);

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long countAfterWipe =
          s.run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(countAfterFirst, countAfterWipe, "Wipe + reingest must yield same node count");
    }
  }

  // --- helpers ---

  @Test
  void reingestionWithoutWipeIsIdempotent() throws Exception {
    currentProject = PROJECT_BASE + "-idem";
    sourceDir = buildSampleSourceTree();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    orchestrator.run(false);
    long countAfterFirst;
    try (Session s = driver.session()) {
      countAfterFirst =
          s.run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
    }

    orchestrator.run(false);

    try (Session s = driver.session()) {
      long countAfterSecond =
          s.run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(countAfterFirst, countAfterSecond, "MERGE-based upsert must be idempotent");
    }
  }

  @Test
  void returnsNonZeroFailureCountForUnparsableFile() throws Exception {
    currentProject = PROJECT_BASE + "-fail";
    sourceDir = Files.createTempDirectory("orch-bad-src-");
    Files.writeString(sourceDir.resolve("Broken.java"), "this {{{ is not java");

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(false);

    assertTrue(failures > 0, "Expected non-zero failures for unparsable .java file");
  }

  @Test
  void ingestsInterfaceNodes() throws Exception {
    currentProject = PROJECT_BASE + "-iface";
    sourceDir = buildSampleSourceTree();

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(false);

    try (Session s = driver.session()) {
      long ifaceCount =
          s.run(
                  "MATCH (i:Interface {project: $p}) RETURN count(i) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(ifaceCount >= 1, "At least one :Interface node expected");
    }
  }
}
