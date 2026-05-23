package io.github.ousatov.tools.memgraph.exe;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.extension.MemgraphExtension;
import io.github.ousatov.tools.memgraph.extension.MemgraphInstance;
import io.github.ousatov.tools.memgraph.schema.Memgraph;
import io.github.ousatov.tools.memgraph.vo.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

  private static boolean classExists(String project, String fqn) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (c:Class {project: $p, fqn: $fqn}) RETURN count(c) AS n",
                  Map.of("p", project, "fqn", fqn))
              .single()
              .get("n")
              .asLong()
          > 0;
    }
  }

  private static void createClassCodeRef(String project, String fqn) {
    try (Session s = driver.session()) {
      s.run(
              "MATCH (m:Memory {project: $p})"
                  + " MERGE (d:Decision {id: 'DEC-watch-refers-to-class', project: $p})"
                  + " SET d.title = 'Watch class reference', d.status = 'accepted'"
                  + " MERGE (ref:CodeRef {project: $p, targetType: 'Class', key: $fqn})"
                  + " MERGE (m)-[:HAS_DECISION]->(d)"
                  + " MERGE (d)-[:REFERS_TO]->(ref)",
              Map.of("p", project, "fqn", fqn))
          .consume();
    }
  }

  private static boolean classCodeRefResolved(String project, String fqn) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (:CodeRef {project: $p, targetType: 'Class', key: $fqn})"
                      + "-[:RESOLVES_TO]->(:Class {project: $p, fqn: $fqn})"
                      + " RETURN count(*) AS n",
                  Map.of("p", project, "fqn", fqn))
              .single()
              .get("n")
              .asLong()
          > 0;
    }
  }

  private static boolean hasNoPhantomMethods(String project) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (m:Method {project: $p})"
                      + " WHERE m.startLine IS NULL"
                      + " RETURN count(m) AS n",
                  Map.of("p", project))
              .single()
              .get("n")
              .asLong()
          == 0;
    }
  }

  /**
   * Minimal JS/TS adapter used to verify multi-adapter orchestration without invoking Node.js.
   *
   * @author Oleksii Usatov
   */
  private static final class StubJsLanguageAdapter implements LanguageAdapter {

    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVASCRIPT;
    }

    @Override
    public boolean accepts(Path file) {
      return file.toString().endsWith(".ts");
    }

    @Override
    public boolean ingestFile(GraphWriter writer, Path file) {
      writer.upsertFile(file, language());
      writer.upsertPackage("js.test", language());
      writer.upsertJavascriptModule(file, "js.test", "js.test.App", "App", "app.ts", 1, 1);
      return true;
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
  void ingestsMatchingFilesThroughLanguageGroups() throws Exception {
    currentProject = PROJECT_BASE + "-languages";
    sourceDir = Files.createTempDirectory("orch-languages-src-");
    Files.writeString(
        sourceDir.resolve("Good.java"), "public class Good { int ok() { return 1; } }");
    Path tsFile = Files.writeString(sourceDir.resolve("app.ts"), "export const app = 1;");

    int failures =
        new IngestionOrchestrator(
                sourceDir,
                currentProject,
                1,
                driver,
                List.of(
                    new JavaLanguageAdapter(new ParseService(sourceDir)),
                    new StubJsLanguageAdapter()))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long javaFiles =
          s.run(
                  "MATCH (:Project {name: $p})-[:CONTAINS]->(:Language {name: 'Java'})"
                      + "-[:CONTAINS]->(:Code {language: 'java'})-[:CONTAINS]->"
                      + "(:File {path: $path, project: $p}) RETURN count(*) AS n",
                  Map.of("p", currentProject, "path", sourceDir.resolve("Good.java").toString()))
              .single()
              .get("n")
              .asLong();
      long jsFiles =
          s.run(
                  "MATCH (:Project {name: $p})-[:CONTAINS]->(:Language {name: 'Js'})"
                      + "-[:CONTAINS]->(:Code {language: 'js'})-[:CONTAINS]->"
                      + "(:File {path: $path, project: $p}) RETURN count(*) AS n",
                  Map.of("p", currentProject, "path", tsFile.toString()))
              .single()
              .get("n")
              .asLong();

      assertEquals(1, javaFiles);
      assertEquals(1, jsFiles);
    }
  }

  @Test
  void appliesLanguageSchemaMigrationWhenLegacySchemaIsDetected() throws Exception {
    currentProject = PROJECT_BASE + "-legacy-schema";
    sourceDir = Files.createTempDirectory("orch-legacy-schema-src-");
    Files.writeString(
        sourceDir.resolve("Good.java"), "public class Good { int ok() { return 1; } }");
    Path tsFile = Files.writeString(sourceDir.resolve("app.ts"), "export const app = 1;");

    try (Session s = driver.session()) {
      Memgraph.wipeAllData(s);
      s.run("CREATE CONSTRAINT ON (p:Project) ASSERT p.name IS UNIQUE").consume();
      s.run("CREATE CONSTRAINT ON (c:Code) ASSERT c.project IS UNIQUE").consume();
      s.run(
              "CREATE (project:Project {name: $p})"
                  + " CREATE (project)-[:CONTAINS]->(:Code {project: $p})",
              Map.of("p", currentProject))
          .consume();
    }

    int failures =
        new IngestionOrchestrator(
                sourceDir,
                currentProject,
                1,
                driver,
                List.of(
                    new JavaLanguageAdapter(new ParseService(sourceDir)),
                    new StubJsLanguageAdapter()))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long jsFiles =
          s.run(
                  "MATCH (:Project {name: $p})-[:CONTAINS]->(:Language {name: 'Js'})"
                      + "-[:CONTAINS]->(:Code {language: 'js'})-[:CONTAINS]->"
                      + "(:File {path: $path, project: $p}) RETURN count(*) AS n",
                  Map.of("p", currentProject, "path", tsFile.toString()))
              .single()
              .get("n")
              .asLong();

      assertEquals(1, jsFiles);
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
                  "MATCH (:Project {name: $p})-[:CONTAINS]->(:Language {name: 'Java'})"
                      + "-[:CONTAINS]->(:Code {project: $p, language: 'java'})"
                      + "-[:CONTAINS]->(:File)-[:DEFINES]->(:Class)"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(codeRootCount >= 1, "Code graph must hang under :Project -> :Language -> :Code");

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
  void watchModeStopsWhenInterrupted() throws Exception {
    currentProject = PROJECT_BASE + "-watch";
    sourceDir = Files.createTempDirectory("orch-watch-src-");
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                orchestrator.run(new Settings(false, true, false, false, false, true));
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "watch-mode-test");
    worker.start();

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> worker.getState() == Thread.State.WAITING);
    await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(() -> true);

    Path watchedFile = sourceDir.resolve("Watched.java");
    createClassCodeRef(currentProject, "Watched");

    try {
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(250))
          .untilAsserted(
              () -> {
                Files.writeString(
                    watchedFile,
                    """
                    public class Watched {
                      public int value(String text) {
                        return text.length();
                      }
                    }
                    """);
                assertTrue(
                    classExists(currentProject, "Watched"), "Watch mode must ingest changed files");
                assertTrue(
                    hasNoPhantomMethods(currentProject),
                    "Watch mode must clean up phantom external Method nodes");
                assertTrue(
                    classCodeRefResolved(currentProject, "Watched"),
                    "Watch mode must refresh CodeRef resolution edges");
              });
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(50))
          .until(() -> worker.getState() == Thread.State.WAITING);
    } finally {
      worker.interrupt();
      worker.join(TimeUnit.SECONDS.toMillis(5));
    }

    assertFalse(worker.isAlive(), "Watch mode must exit after interruption");
    assertNull(failure.get(), () -> "Watch mode failed: " + failure.get());
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
  void ingestsEnumRecordAndAnnotationTopLevelTypes() throws Exception {
    currentProject = PROJECT_BASE + "-mixed-types";
    sourceDir = Files.createTempDirectory("orch-mixed-types-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("AllTypes.java"),
        """
        package com.example;

        @interface Marker {}

        enum Status {
          ACTIVE
        }

        record Point(int x) {}

        class Holder {
          @Marker void marked() {}
          Status status;
          Point point;
        }
        """);

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long enumCount =
          s.run(
                  "MATCH (c:Class {project: $p, fqn: 'com.example.Status', isEnum: true})"
                      + " RETURN count(c) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      long recordCount =
          s.run(
                  "MATCH (c:Class {project: $p, fqn: 'com.example.Point', isRecord: true})"
                      + " RETURN count(c) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      long annotationCount =
          s.run(
                  "MATCH (a:Annotation {project: $p, fqn: 'com.example.Marker'})"
                      + " RETURN count(a) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();

      assertEquals(1, enumCount);
      assertEquals(1, recordCount);
      assertEquals(1, annotationCount);
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

    int failures = orchestrator.run(new Settings(false, false, false, true, false, false));

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

    // "AAA" sorts before "BBB" — guarantees AAA is processed first (inline MERGE-on-callee test)
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

      List<String> ownerEdges =
          s.run(
                  "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {project: $p})"
                      + " WHERE caller.ownerFqn IS NOT NULL"
                      + " AND callee.ownerFqn IS NOT NULL"
                      + " AND caller.ownerFqn <> callee.ownerFqn"
                      + " RETURN caller.ownerDisplayName + ' -> ' + callee.ownerDisplayName"
                      + " AS edge ORDER BY edge",
                  Map.of("p", currentProject))
              .list(r -> r.get("edge").asString());
      assertTrue(
          ownerEdges.contains("AAACaller -> BBBService"),
          "CALLS summaries should use persisted Method owner metadata");
    }
  }

  @Test
  void incrementalRunBackfillsOwnerMetadataForUnchangedMethods() throws Exception {
    currentProject = PROJECT_BASE + "-incremental-owner-backfill";
    sourceDir = Files.createTempDirectory("orch-incremental-owner-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
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

    var orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, orchestrator.run(Settings.def()));

    try (Session s = driver.session()) {
      s.run(
              "MATCH (m:Method {project: $p}) REMOVE m.ownerFqn, m.ownerDisplayName",
              Map.of("p", currentProject))
          .consume();
    }

    assertEquals(0, orchestrator.run(new Settings(false, false, false, false, true, false)));

    try (Session s = driver.session()) {
      long missingOwnerMetadata =
          s.run(
                  "MATCH (owner)-[:DECLARES]->(m:Method {project: $p})"
                      + " WHERE owner.project = $p"
                      + " AND (owner:Class OR owner:Interface OR owner:Annotation)"
                      + " AND (m.ownerFqn IS NULL OR m.ownerDisplayName IS NULL)"
                      + " RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(0, missingOwnerMetadata, "Incremental run must backfill skipped methods");

      List<String> ownerEdges =
          s.run(
                  "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {project: $p})"
                      + " WHERE caller.ownerFqn IS NOT NULL"
                      + " AND callee.ownerFqn IS NOT NULL"
                      + " AND caller.ownerFqn <> callee.ownerFqn"
                      + " RETURN caller.ownerDisplayName + ' -> ' + callee.ownerDisplayName"
                      + " AS edge ORDER BY edge",
                  Map.of("p", currentProject))
              .list(r -> r.get("edge").asString());
      assertTrue(ownerEdges.contains("AAACaller -> BBBService"));
    }
  }

  @Test
  void incrementalRunReingestsChangedInvalidAndNewFiles() throws Exception {
    currentProject = PROJECT_BASE + "-incremental-changes";
    sourceDir = buildSampleSourceTree();

    var orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, orchestrator.run(Settings.def()));

    Path widgetFile = sourceDir.resolve("com/example/Widget.java");
    Files.writeString(
        widgetFile,
        """
        package com.example;

        public class Widget {
          public String changed() {
            return "changed";
          }
        }
        """);
    Files.setLastModifiedTime(
        widgetFile, FileTime.fromMillis(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)));

    Path newFile = sourceDir.resolve("com/example/NewThing.java");
    Files.writeString(
        newFile,
        """
        package com.example;

        public class NewThing {}
        """);

    Path interfaceFile = sourceDir.resolve("com/example/Describable.java");
    try (Session s = driver.session()) {
      s.run(
              "MATCH (f:File {project: $p, path: $path}) SET f.lastModified = 0",
              Map.of("p", currentProject, "path", interfaceFile.toString()))
          .consume();
    }

    assertEquals(0, orchestrator.run(new Settings(false, false, false, false, true, false)));

    try (Session s = driver.session()) {
      long newThingCount =
          s.run(
                  "MATCH (c:Class {project: $p, fqn: 'com.example.NewThing'}) RETURN count(c) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      long changedMethodCount =
          s.run(
                  "MATCH (m:Method {project: $p, signature: 'com.example.Widget.changed()'})"
                      + " RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();

      assertEquals(1, newThingCount);
      assertEquals(1, changedMethodCount);
    }
  }

  @Test
  void incrementalModeIsDisabledWhenWipingProjectData() throws Exception {
    currentProject = PROJECT_BASE + "-incremental-wipe";
    sourceDir = buildSampleSourceTree();
    var orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    assertEquals(0, orchestrator.run(Settings.def()));
    assertEquals(0, orchestrator.run(new Settings(false, false, true, false, true, false)));
    assertEquals(0, orchestrator.run(new Settings(true, false, false, false, true, false)));

    try (Session s = driver.session()) {
      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();

      assertTrue(classCount >= 1);
    }
  }

  /**
   * Verifies that inline CALLS creation works when the callee file is processed after the caller —
   * the MERGE-on-callee trick creates a placeholder that is upgraded when BBBService is ingested.
   */
  @Test
  void inlineCallEdgesCreatedWhenCalleeProcessedLater() throws Exception {
    currentProject = PROJECT_BASE + "-inline";
    sourceDir = Files.createTempDirectory("orch-inline-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);

    // "AAA" sorts before "ZZZ" — caller always processed before callee
    Files.writeString(
        pkgDir.resolve("AAAInlineCaller.java"),
        """
        package com.example;

        public class AAAInlineCaller {
          public static void call() {
            ZZZInlineTarget.process();
          }
        }
        """);
    Files.writeString(
        pkgDir.resolve("ZZZInlineTarget.java"),
        """
        package com.example;

        public class ZZZInlineTarget {
          public static void process() {}
        }
        """);

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long callEdges =
          s.run(
                  "MATCH (:Method {name: 'call', project: $p})"
                      + "-[:CALLS]->(:Method {name: 'process', project: $p})"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(
          1, callEdges, "CALLS edge must exist even though callee was processed after caller");

      // The callee must be a fully populated node (not a phantom) after ingestion
      long phantomMethods =
          s.run(
                  "MATCH (m:Method {project: $p}) WHERE m.startLine IS NULL RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(0, phantomMethods, "No phantom Method nodes must remain after phantom cleanup");
    }
  }

  /** Verifies that phantom Method nodes created for JDK/external callees are cleaned up. */
  @Test
  void phantomExternalMethodNodesCleanedUpAfterIngestion() throws Exception {
    currentProject = PROJECT_BASE + "-phantom";
    sourceDir = Files.createTempDirectory("orch-phantom-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);

    // Calls String.length() — a JDK method that will never be ingested
    Files.writeString(
        pkgDir.resolve("PhantomCaller.java"),
        """
        package com.example;

        public class PhantomCaller {
          public int getLen(String s) {
            return s.length();
          }
        }
        """);

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long phantomMethods =
          s.run(
                  "MATCH (m:Method {project: $p}) WHERE m.startLine IS NULL RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(
          0, phantomMethods, "Phantom external Method nodes must be removed after ingestion");
    }
  }

  /**
   * 20 caller files all call the same static method on a target that is alphabetically last —
   * verifying that all 20 CALLS edges are created via the MERGE-on-callee approach even though the
   * target file is processed after all callers.
   */
  @Test
  void manyCrossFileCallsAllCreatedInline() throws Exception {
    currentProject = PROJECT_BASE + "-many-inline";
    sourceDir = Files.createTempDirectory("orch-many-inline-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);

    // "ZZZ" sorts after all "Caller" files — target always processed last
    Files.writeString(
        pkgDir.resolve("ZZZSharedTarget.java"),
        """
        package com.example;

        public class ZZZSharedTarget {
          public static void work() {}
        }
        """);

    int callerCount = 20;
    for (int i = 0; i < callerCount; i++) {
      Files.writeString(
          pkgDir.resolve(String.format("Caller%02d.java", i)),
          """
          package com.example;

          public class Caller%02d {
            public void run() {
              ZZZSharedTarget.work();
            }
          }
          """
              .formatted(i));
    }

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long callEdges =
          s.run(
                  "MATCH (:Method {project: $p})-[:CALLS]->(:Method {name: 'work', project: $p})"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(
          callerCount,
          callEdges,
          "Each caller must have exactly one CALLS edge to ZZZSharedTarget.work()");

      long phantomMethods =
          s.run(
                  "MATCH (m:Method {project: $p}) WHERE m.startLine IS NULL RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(0, phantomMethods, "No phantom nodes must survive after ingestion");
    }
  }

  /**
   * A class with no declared constructor must have a synthesized {@code <init>()} node so that
   * {@code new ClassName()} CALLS edges survive phantom cleanup.
   */
  @Test
  void callsEdgeToImplicitDefaultConstructorPreservedAfterIngestion() throws Exception {
    currentProject = PROJECT_BASE + "-implicit-ctor";
    sourceDir = Files.createTempDirectory("orch-implicit-ctor-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Service.java"),
        """
        package com.example;
        public class Service {
          public void serve() {}
        }
        """);
    Files.writeString(
        pkgDir.resolve("Client.java"),
        """
        package com.example;
        public class Client {
          public void run() {
            new Service().serve();
          }
        }
        """);

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long initEdges =
          s.run(
                  "MATCH (:Method {project: $p})-[:CALLS]->(m:Method {name: '<init>', project: $p})"
                      + " RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(
          initEdges >= 1,
          "CALLS edge to implicit default constructor must survive phantom cleanup");

      long phantomMethods =
          s.run(
                  "MATCH (m:Method {project: $p}) WHERE m.startLine IS NULL RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(0, phantomMethods, "No phantom nodes must remain after ingestion");
    }
  }

  /** Sequential and parallel ingestion must produce the same CALLS edge count. */
  @Test
  void sequentialAndParallelProduceSameCallEdgeCount() throws Exception {
    Path seqDir = null;
    Path parDir = null;
    String seqProject = PROJECT_BASE + "-calls-seq";
    String parProject = PROJECT_BASE + "-calls-par";
    try {
      seqDir = Files.createTempDirectory("orch-calls-seq-");
      parDir = Files.createTempDirectory("orch-calls-par-");
      for (Path dir : List.of(seqDir, parDir)) {
        Path pkgDir = dir.resolve("com/example");
        Files.createDirectories(pkgDir);
        Files.writeString(
            pkgDir.resolve("Svc.java"),
            """
            package com.example;
            public class Svc { public static void go() {} }
            """);
        for (int i = 0; i < 5; i++) {
          Files.writeString(
              pkgDir.resolve("Client" + i + ".java"),
              """
              package com.example;
              public class Client%s { public void run() { Svc.go(); } }
              """
                  .formatted(i));
        }
      }

      new IngestionOrchestrator(seqDir, seqProject, 1, driver, new ParseService(seqDir))
          .run(Settings.def());
      new IngestionOrchestrator(parDir, parProject, 4, driver, new ParseService(parDir))
          .run(Settings.def());

      try (Session s = driver.session()) {
        long seqCalls =
            s.run(
                    "MATCH (:Method {project: $p})-[:CALLS]->(:Method {project: $p})"
                        + " RETURN count(*) AS n",
                    Map.of("p", seqProject))
                .single()
                .get("n")
                .asLong();
        long parCalls =
            s.run(
                    "MATCH (:Method {project: $p})-[:CALLS]->(:Method {project: $p})"
                        + " RETURN count(*) AS n",
                    Map.of("p", parProject))
                .single()
                .get("n")
                .asLong();
        assertEquals(
            seqCalls, parCalls, "Sequential and parallel must produce equal CALLS edge count");
        assertTrue(seqCalls >= 5, "At least 5 CALLS edges expected (one per client)");
      }
    } finally {
      wipeProjectCode(seqProject);
      wipeProjectCode(parProject);
      deleteDir(seqDir);
      deleteDir(parDir);
    }
  }
}
