package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.extension.MemgraphExtension;
import io.github.ousatov.tools.memgraph.extension.MemgraphInstance;
import io.github.ousatov.tools.memgraph.vo.Settings;
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

  private static void wipeProjectCode(String project) {
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
      wipeProjectCode(currentProject);
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
  void ingestsSequentiallyWithZeroFailuresInitSchema() throws Exception {
    currentProject = PROJECT_BASE + "-seq";
    sourceDir = buildSampleSourceTree();

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.applySchemaOnly());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(classCount >= 1, "At least one :Class node expected after ingestion");

      long codeRootCount =
          s.run(
                  "MATCH (:Project {name: $p})-[:CONTAINS]->(:Code {project: $p})"
                      + "-[:CONTAINS]->(:File)-[:DEFINES]->(:Class)"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(codeRootCount >= 1, "Code graph must hang under :Project -> :Code");

      long memoryRootCount =
          s.run(
                  "MATCH (:Project {name: $p})-[:HAS_MEMORY]->(:Memory {project: $p})"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(1, memoryRootCount, "Memory graph must hang under :Project -> :Memory");
    }
  }

  @Test
  void ingestsSequentiallyWithZeroFailuresWipeAll() throws Exception {
    currentProject = PROJECT_BASE + "-seq";
    sourceDir = buildSampleSourceTree();

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.wipeAllAndApplySchema());
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
  void ingestsSequentiallyWithZeroFailures() throws Exception {
    currentProject = PROJECT_BASE + "-seq";
    sourceDir = buildSampleSourceTree();

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

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
            .run(Settings.def());

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
  void ingestsRepeatedAnnotationsInParallelWithZeroFailures() throws Exception {
    currentProject = PROJECT_BASE + "-ann-par";
    sourceDir = Files.createTempDirectory("orch-ann-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    for (int i = 0; i < 40; i++) {
      Files.writeString(
          pkgDir.resolve("Annotated" + i + ".java"),
          """
          package com.example;

          @Deprecated
          public class Annotated%s {}
          """
              .formatted(i));
    }

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 8, driver, new ParseService(sourceDir))
            .run(Settings.wipeAllAndApplySchema());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long annotationCount =
          s.run(
                  "MATCH (a:Annotation {project: $p, fqn: 'java.lang.Deprecated'})"
                      + " RETURN count(a) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(1, annotationCount, "Repeated annotations must converge to one node");
    }
  }

  @Test
  void parallelAndSequentialIngestSameNodeCount() throws Exception {
    Path seqDir = buildSampleSourceTree();
    Path parDir = buildSampleSourceTree();
    String seqProject = PROJECT_BASE + "-cmp-seq";
    String parProject = PROJECT_BASE + "-cmp-par";

    try {
      new IngestionOrchestrator(seqDir, seqProject, 1, driver, new ParseService(seqDir))
          .run(Settings.def());
      new IngestionOrchestrator(parDir, parProject, 4, driver, new ParseService(parDir))
          .run(Settings.def());

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
      wipeProjectCode(seqProject);
      wipeProjectCode(parProject);
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

    orchestrator.run(Settings.def());
    long countAfterFirst;
    try (Session s = driver.session()) {
      countAfterFirst =
          s.run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
    }

    int failures = orchestrator.run(Settings.wipeProjCodeOnly());

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

  @Test
  void wipeAllMemoriesDeletesMemoryGraphBeforeReingest() throws Exception {
    currentProject = PROJECT_BASE + "-wipe-mem";
    sourceDir = buildSampleSourceTree();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    orchestrator.run(Settings.def());
    try (Session s = driver.session()) {
      s.run(
              "MATCH (m:Memory {project: $p})"
                  + " MERGE (d:Decision {id: 'DEC-test-orchestrator-wipe-memories', project: $p})"
                  + " SET d.status = 'accepted'"
                  + " MERGE (m)-[:HAS_DECISION]->(d)",
              Map.of("p", currentProject))
          .consume();
    }

    int failures = orchestrator.run(new Settings(false, false, false, true, false));

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long decisionCount =
          s.run(
                  "MATCH (d:Decision {id: 'DEC-test-orchestrator-wipe-memories', project: $p})"
                      + " RETURN count(d) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(0, decisionCount, "Memory wipe must delete existing decisions");

      long memoryRootCount =
          s.run(
                  "MATCH (:Project {name: $p})-[:HAS_MEMORY]->(:Memory {project: $p})"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(1, memoryRootCount, "Reingest must recreate an empty Memory root");

      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(classCount >= 1, "Code graph must still be present after memory-only wipe");
    }
  }

  @Test
  void reingestionWithoutWipeIsIdempotent() throws Exception {
    currentProject = PROJECT_BASE + "-idem";
    sourceDir = buildSampleSourceTree();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    orchestrator.run(Settings.def());
    long countAfterFirst;
    try (Session s = driver.session()) {
      countAfterFirst =
          s.run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
    }

    orchestrator.run(Settings.def());

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
  void ingestsConstructorNodes() throws Exception {
    currentProject = PROJECT_BASE + "-ctor";
    sourceDir = Files.createTempDirectory("orch-ctor-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Service.java"),
        """
        package com.example;

        public class Service {
          private final String name;

          public Service(String name) {
            this.name = name;
          }

          public Service() {
            this("default");
          }
        }
        """);

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long ctorCount =
          s.run(
                  "MATCH (:Class {fqn: 'com.example.Service', project: $p})"
                      + "-[:DECLARES]->(m:Method {name: '<init>'})"
                      + " RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(2, ctorCount, "Both constructors of Service must be ingested");
    }
  }

  @Test
  void returnsNonZeroFailureCountForUnparsableFile() throws Exception {
    currentProject = PROJECT_BASE + "-fail";
    sourceDir = Files.createTempDirectory("orch-bad-src-");
    Files.writeString(sourceDir.resolve("Broken.java"), "this {{{ is not java");

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertTrue(failures > 0, "Expected non-zero failures for unparsable .java file");
  }

  @Test
  void ingestsInterfaceNodes() throws Exception {
    currentProject = PROJECT_BASE + "-iface";
    sourceDir = buildSampleSourceTree();

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

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

  @Test
  void ingestsInterfaceExtendsAsInterfaceParent() throws Exception {
    currentProject = PROJECT_BASE + "-iext";
    sourceDir = Files.createTempDirectory("orch-iext-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Printable.java"), "package com.example; public interface Printable {}");
    Files.writeString(
        pkgDir.resolve("Describable.java"),
        "package com.example; public interface Describable extends Printable {}");

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long count =
          s.run(
                  "MATCH (:Interface {project: $p})-[:EXTENDS]->(parent:Interface)"
                      + " RETURN count(parent) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(count >= 1, "Interface parent must be stored as :Interface, not :Class");
    }
  }

  @Test
  void ingestsNestedClassWithCorrectFqn() throws Exception {
    currentProject = PROJECT_BASE + "-nested";
    sourceDir = Files.createTempDirectory("orch-nested-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Outer.java"),
        """
        package com.example;
        public class Outer {
          public class Inner {}
        }
        """);

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long outerCount =
          s.run(
                  "MATCH (c:Class {fqn: 'com.example.Outer', project: $p}) RETURN count(c) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(1, outerCount, "Outer class must have correct FQN");

      long innerCount =
          s.run(
                  "MATCH (c:Class {fqn: 'com.example.Outer$Inner', project: $p})"
                      + " RETURN count(c) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(1, innerCount, "Nested class must use $-separated FQN");
    }
  }

  @Test
  void crossFileCallsEdgesCreated() throws Exception {
    currentProject = PROJECT_BASE + "-xfile";
    sourceDir = Files.createTempDirectory("orch-xfile-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);

    // "AAA" sorts before "BBB" — guarantees AAA is processed first in phase 1
    Files.writeString(
        pkgDir.resolve("AAACaller.java"),
        """
        package com.example;

        public class AAACaller {
          public void doWork() {
            new BBBService().serve();
          }
        }
        """);
    Files.writeString(
        pkgDir.resolve("BBBService.java"),
        """
        package com.example;

        public class BBBService {
          public void serve() {}
        }
        """);

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long callEdges =
          s.run(
                  "MATCH (:Method {project: $p})-[:CALLS]->(:Method {project: $p})"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(callEdges >= 1, "Cross-file CALLS edge must exist (AAACaller -> BBBService)");
    }
  }
}
