package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.github.ousatov.tools.memgraph.extension.MemgraphExtension;
import io.github.ousatov.tools.memgraph.extension.MemgraphInstance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * Integration tests for {@link GraphWriter} against a live Memgraph instance.
 *
 * <p>Each test gets a fresh {@link Session} and {@link GraphWriter}. Data is wiped in
 * {@code @AfterEach} so tests are fully independent.
 *
 * @author Oleksii Usatov
 */
@ExtendWith(MemgraphExtension.class)
class GraphWriterIT {

  private static final String PROJECT = "test-gw-" + UUID.randomUUID().toString().substring(0, 8);
  private static final Path SRC_ROOT = Path.of("/tmp/test-gw/src");
  private static final Path TEST_FILE = Path.of("/tmp/test-gw/src/com/example/Widget.java");
  private static final String PKG = "com.example";

  private static Driver driver;
  private Session session;
  private GraphWriter writer;

  @BeforeAll
  static void setupDriver(MemgraphInstance mg) {
    driver = GraphDatabase.driver(mg.getBoltUrl(), AuthTokens.basic("", ""));
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
  void upsertProjectCreatesProjectCodeAndMemoryNodes() {
    long count =
        session
            .run("MATCH (p:Project {name: $name}) RETURN count(p) AS n", Map.of("name", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);

    long codeCount =
        session
            .run(
                "MATCH (:Project {name: $name})-[:CONTAINS]->(c:Code {project: $name})"
                    + " RETURN count(c) AS n",
                Map.of("name", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, codeCount);

    long memoryCount =
        session
            .run(
                "MATCH (:Project {name: $name})-[:HAS_MEMORY]->(m:Memory {project: $name})"
                    + " RETURN count(m) AS n",
                Map.of("name", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, memoryCount);
  }

  @Test
  void upsertProjectIsIdempotent() {
    writer.upsertProject(SRC_ROOT);
    writer.upsertProject(SRC_ROOT);

    long count =
        session
            .run("MATCH (p:Project {name: $name}) RETURN count(p) AS n", Map.of("name", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);

    long codeCount =
        session
            .run("MATCH (c:Code {project: $name}) RETURN count(c) AS n", Map.of("name", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, codeCount);

    long memoryCount =
        session
            .run("MATCH (m:Memory {project: $name}) RETURN count(m) AS n", Map.of("name", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, memoryCount);

    long memoryLinkCount =
        session
            .run(
                "MATCH (:Project {name: $name})-[r:HAS_MEMORY]->(:Memory {project: $name})"
                    + " RETURN count(r) AS n",
                Map.of("name", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, memoryLinkCount);
  }

  @Test
  void upsertFileCreatesFileWithContainsEdge() {
    writer.upsertFile(TEST_FILE);

    long count =
        session
            .run(
                "MATCH (:Project {name: $p})-[:CONTAINS]->(:Code)-[:CONTAINS]->"
                    + "(f:File {path: $path})"
                    + " RETURN count(f) AS n",
                Map.of("p", PROJECT, "path", TEST_FILE.toString()))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);
  }

  @Test
  void upsertFileIsIdempotent() {
    writer.upsertFile(TEST_FILE);
    writer.upsertFile(TEST_FILE);

    long count =
        session
            .run("MATCH (f:File {project: $p}) RETURN count(f) AS n", Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);
  }

  @Test
  void upsertFileWritesLastModified() throws IOException {
    Path tempFile = Files.createTempFile("widget-", ".java");
    try {
      writer.upsertFile(tempFile);

      long lastModified =
          session
              .run(
                  "MATCH (f:File {path: $path, project: $p}) RETURN f.lastModified AS lm",
                  Map.of("path", tempFile.toString(), "p", PROJECT))
              .single()
              .get("lm")
              .asLong();

      assertTrue(lastModified > 0, "lastModified must be a positive epoch-millis value");
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  void upsertPackageCreatesPackageWithContainsEdge() {
    writer.upsertPackage(PKG);

    long count =
        session
            .run(
                "MATCH (:Project {name: $p})-[:CONTAINS]->(:Code)-[:CONTAINS]->"
                    + "(pkg:Package {name: $name})"
                    + " RETURN count(pkg) AS n",
                Map.of("p", PROJECT, "name", PKG))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);
  }

  @Test
  void upsertTypeCreatesClassNode() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl("package com.example; public class Widget { private String name; }");

    writer.upsertType(TEST_FILE, PKG, decl);

    var rec =
        session
            .run(
                "MATCH (c:Class {project: $p, fqn: $fqn})"
                    + " RETURN c.name AS name, c.isAbstract AS isAbs",
                Map.of("p", PROJECT, "fqn", "com.example.Widget"))
            .single();

    assertEquals("Widget", rec.get("name").asString());
    assertFalse(rec.get("isAbs").asBoolean());
  }

  @Test
  void upsertTypeWritesVisibility() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl = parseDecl("package com.example; public class Widget {}");

    writer.upsertType(TEST_FILE, PKG, decl);

    String visibility =
        session
            .run(
                "MATCH (c:Class {project: $p, fqn: $fqn}) RETURN c.visibility AS v",
                Map.of("p", PROJECT, "fqn", "com.example.Widget"))
            .single()
            .get("v")
            .asString();

    assertEquals("public", visibility);
  }

  @Test
  void upsertTypeCreatesInterfaceNode() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl("package com.example; public interface Describable { String describe(); }");

    writer.upsertType(TEST_FILE, PKG, decl);

    long count =
        session
            .run(
                "MATCH (i:Interface {project: $p, fqn: $fqn}) RETURN count(i) AS n",
                Map.of("p", PROJECT, "fqn", "com.example.Describable"))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);
  }

  @Test
  void upsertTypeCreatesAbstractClassNode() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl(
            "package com.example; public abstract class BaseWidget { abstract void init(); }");

    writer.upsertType(TEST_FILE, PKG, decl);

    boolean isAbstract =
        session
            .run(
                "MATCH (c:Class {project: $p, fqn: $fqn}) RETURN c.isAbstract AS v",
                Map.of("p", PROJECT, "fqn", "com.example.BaseWidget"))
            .single()
            .get("v")
            .asBoolean();

    assertTrue(isAbstract);
  }

  @Test
  void upsertTypeCreatesFieldNodes() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl(
            "package com.example;"
                + " public class Widget {"
                + "   private String name;"
                + "   private static int count;"
                + " }");

    writer.upsertType(TEST_FILE, PKG, decl);

    List<String> fieldNames =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(f:Field)"
                    + " RETURN f.name AS n ORDER BY f.name",
                Map.of("fqn", "com.example.Widget", "p", PROJECT))
            .list(r -> r.get("n").asString());

    assertEquals(List.of("count", "name"), fieldNames);
  }

  @Test
  void upsertTypeCreatesStaticFieldNode() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl("package com.example; public class Widget { private static int count; }");

    writer.upsertType(TEST_FILE, PKG, decl);

    boolean isStatic =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(f:Field {name: 'count'})"
                    + " RETURN f.isStatic AS v",
                Map.of("fqn", "com.example.Widget", "p", PROJECT))
            .single()
            .get("v")
            .asBoolean();

    assertTrue(isStatic);
  }

  @Test
  void upsertTypeCreatesMethodNodes() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl(
            "package com.example;"
                + " public class Widget {"
                + "   private String name;"
                + "   public String getName() { return name; }"
                + "   public static int getCount() { return 0; }"
                + " }");

    writer.upsertType(TEST_FILE, PKG, decl);

    List<String> methodNames =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(m:Method)"
                    + " RETURN m.name AS n ORDER BY m.name",
                Map.of("fqn", "com.example.Widget", "p", PROJECT))
            .list(r -> r.get("n").asString());

    assertTrue(methodNames.contains("getName"));
    assertTrue(methodNames.contains("getCount"));
  }

  @Test
  void upsertTypeLinksTypeToFileAndPackage() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl = parseDecl("package com.example; public class Widget {}");

    writer.upsertType(TEST_FILE, PKG, decl);

    long fileLink =
        session
            .run(
                "MATCH (:File {path: $path, project: $p})-[:DEFINES]->(c:Class {fqn: $fqn})"
                    + " RETURN count(c) AS n",
                Map.of("path", TEST_FILE.toString(), "p", PROJECT, "fqn", "com.example.Widget"))
            .single()
            .get("n")
            .asLong();

    long pkgLink =
        session
            .run(
                "MATCH (:Package {name: $pkg, project: $p})-[:CONTAINS]->(c:Class {fqn: $fqn})"
                    + " RETURN count(c) AS n",
                Map.of("pkg", PKG, "p", PROJECT, "fqn", "com.example.Widget"))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, fileLink);
    assertEquals(1, pkgLink);
  }

  @Test
  void upsertTypeCreatesConstructorNode() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl(
            "package com.example;" + " public class Widget {" + "   public Widget() {}" + " }");

    writer.upsertType(TEST_FILE, PKG, decl);

    long count =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(m:Method {name: '<init>'})"
                    + " RETURN count(m) AS n",
                Map.of("fqn", "com.example.Widget", "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);
  }

  @Test
  void upsertTypeCreatesMultipleConstructors() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl(
            "package com.example;"
                + " public class Widget {"
                + "   public Widget() {}"
                + "   public Widget(String name) {}"
                + " }");

    writer.upsertType(TEST_FILE, PKG, decl);

    long count =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(m:Method {name: '<init>'})"
                    + " RETURN count(m) AS n",
                Map.of("fqn", "com.example.Widget", "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(2, count);
  }

  @Test
  void upsertTypeConstructorSignatureIncludesParams() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl(
            "package com.example;"
                + " public class Widget {"
                + "   public Widget(String name, int count) {}"
                + " }");

    writer.upsertType(TEST_FILE, PKG, decl);

    String sig =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(m:Method {name: '<init>'})"
                    + " RETURN m.signature AS s",
                Map.of("fqn", "com.example.Widget", "p", PROJECT))
            .single()
            .get("s")
            .asString();

    // Without symbol resolution the fallback uses simple type names.
    assertEquals("com.example.Widget.<init>(String, int)", sig);
  }

  @Test
  void wipeDeletesOnlyProjectCodeNodes() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    session
        .run(
            "MATCH (m:Memory {project: $p})"
                + " MERGE (d:Decision {id: 'DEC-test-preserved', project: $p})"
                + " SET d.status = 'accepted', d.title = 'Preserved decision'"
                + " MERGE (m)-[:HAS_DECISION]->(d)",
            Map.of("p", PROJECT))
        .consume();

    writer.wipe();

    long codeCount =
        session
            .run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(2, codeCount);

    long remainingCodeCount =
        session
            .run(
                "MATCH (n) WHERE n.project = $p"
                    + " AND (n:Code OR n:Package OR n:File OR n:Class OR n:Interface"
                    + " OR n:Annotation OR n:Method OR n:Field)"
                    + " RETURN count(n) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(0, remainingCodeCount);

    long projectCount =
        session
            .run("MATCH (p:Project {name: $p}) RETURN count(p) AS n", Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, projectCount);

    long memoryCount =
        session
            .run(
                "MATCH (:Project {name: $p})-[:HAS_MEMORY]->(:Memory {project: $p})"
                    + "-[:HAS_DECISION]->(:Decision {id: 'DEC-test-preserved', project: $p})"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, memoryCount);
  }

  @Test
  void upsertEnumCreatesFieldNodes() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    EnumDeclaration decl =
        parseEnum(
            "package com.example;"
                + " public enum Status {"
                + "   ACTIVE, INACTIVE;"
                + "   private final String label = \"x\";"
                + " }");

    writer.upsertEnum(TEST_FILE, PKG, decl);

    List<String> fieldNames =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(f:Field)"
                    + " RETURN f.name AS n ORDER BY f.name",
                Map.of("fqn", "com.example.Status", "p", PROJECT))
            .list(r -> r.get("n").asString());

    assertEquals(List.of("label"), fieldNames);
  }

  @Test
  void upsertEnumWritesImplementsEdge() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    EnumDeclaration decl =
        parseEnumResolved(
            "package com.example;"
                + " public enum Status implements java.io.Serializable {"
                + "   ACTIVE, INACTIVE;"
                + " }");

    writer.upsertEnum(TEST_FILE, PKG, decl);

    long count =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:IMPLEMENTS]->(i:Interface)"
                    + " RETURN count(i) AS n",
                Map.of("fqn", "com.example.Status", "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);
  }

  @Test
  void upsertRecordCreatesFieldNodes() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    RecordDeclaration decl =
        parseRecord("package com.example;" + " public record Point(int x, int y) {}");

    writer.upsertRecord(TEST_FILE, PKG, decl);

    List<String> fieldNames =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(f:Field)"
                    + " RETURN f.name AS n ORDER BY f.name",
                Map.of("fqn", "com.example.Point", "p", PROJECT))
            .list(r -> r.get("n").asString());

    assertEquals(List.of("x", "y"), fieldNames);
  }

  @Test
  void upsertRecordWritesImplementsEdge() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    RecordDeclaration decl =
        parseRecordResolved(
            "package com.example;"
                + " public record Point(int x, int y) implements java.io.Serializable {}");

    writer.upsertRecord(TEST_FILE, PKG, decl);

    long count =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:IMPLEMENTS]->(i:Interface)"
                    + " RETURN count(i) AS n",
                Map.of("fqn", "com.example.Point", "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, count);
  }

  @Test
  void fileTransactionCommitPersistsWrites() {
    writer.beginFileTransaction();
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    writer.commitFileTransaction();

    long fileCount =
        session
            .run(
                "MATCH (f:File {path: $path, project: $p}) RETURN count(f) AS n",
                Map.of("path", TEST_FILE.toString(), "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, fileCount);

    long pkgCount =
        session
            .run(
                "MATCH (pkg:Package {name: $name, project: $p}) RETURN count(pkg) AS n",
                Map.of("name", PKG, "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, pkgCount);
  }

  @Test
  void fileTransactionRollbackDiscardsWrites() {
    writer.beginFileTransaction();
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    writer.rollbackFileTransaction();

    long fileCount =
        session
            .run(
                "MATCH (f:File {path: $path, project: $p}) RETURN count(f) AS n",
                Map.of("path", TEST_FILE.toString(), "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(0, fileCount);
  }

  @Test
  void fileTransactionIsIdempotentForMultipleFiles() {
    Path file2 = Path.of("/tmp/test-gw/src/com/example/Service.java");

    writer.beginFileTransaction();
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    writer.commitFileTransaction();

    writer.beginFileTransaction();
    writer.upsertFile(file2);
    writer.upsertPackage(PKG);
    writer.commitFileTransaction();

    long fileCount =
        session
            .run("MATCH (f:File {project: $p}) RETURN count(f) AS n", Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(2, fileCount);

    long pkgCount =
        session
            .run(
                "MATCH (pkg:Package {name: $name, project: $p}) RETURN count(pkg) AS n",
                Map.of("name", PKG, "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, pkgCount);
  }

  @Test
  void extendsMarksExternalParentAsExternal() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDeclResolved(
            "package com.example;" + " public class MyException extends RuntimeException {}");

    writer.upsertType(TEST_FILE, PKG, decl);

    var result =
        session
            .run(
                "MATCH (c:Class {fqn: $fqn, project: $p})"
                    + " RETURN c.isExternal AS ext, c.name AS name",
                Map.of("fqn", "com.example.MyException", "p", PROJECT))
            .single();

    assertFalse(result.get("ext").asBoolean());
    assertEquals("MyException", result.get("name").asString());
  }

  @Test
  void extendsMarksPhantomParentWithInferredProperties() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDeclResolved(
            "package com.example;" + " public class MyException extends RuntimeException {}");

    writer.upsertType(TEST_FILE, PKG, decl);

    var result =
        session
            .run(
                "MATCH (:Class {fqn: 'com.example.MyException', project: $p})"
                    + "-[:EXTENDS]->(parent:Class)"
                    + " RETURN parent.isExternal AS ext, parent.name AS name,"
                    + " parent.packageName AS pkg",
                Map.of("p", PROJECT))
            .single();

    assertTrue(result.get("ext").asBoolean());
    assertEquals("RuntimeException", result.get("name").asString());
    assertEquals("java.lang", result.get("pkg").asString());
  }

  @Test
  void callsFallbackCreatesEdgeForUnscopedSameClassCall() throws IOException {
    Path tempDir = Files.createTempDirectory("call-fallback-test");
    Path sourceFile = tempDir.resolve("com/example/Service.java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(
        sourceFile,
        "package com.example;"
            + " public class Service {"
            + "   private void helper() {}"
            + "   public void doWork() { helper(); }"
            + " }");

    ParseService parseService = new ParseService(tempDir);
    var cu = parseService.parse(sourceFile).orElseThrow();
    var decl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

    writer.upsertFile(sourceFile);
    writer.upsertPackage("com.example");
    writer.upsertType(sourceFile, "com.example", decl);
    writer.upsertTypeCallEdges("com.example", decl);

    long callCount =
        session
            .run(
                "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {name: 'helper'})"
                    + " WHERE caller.name = 'doWork'"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, callCount);
    deleteDir(tempDir);
  }

  @Test
  void methodReferenceCreatesCallsEdge() throws IOException {
    Path tempDir = Files.createTempDirectory("method-ref-test");
    Path sourceFile = tempDir.resolve("com/example/Mapper.java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(
        sourceFile,
        "package com.example;"
            + " import java.util.List;"
            + " public class Mapper {"
            + "   private static String transform(String s) { return s; }"
            + "   public List<String> map(List<String> items) {"
            + "     return items.stream().map(Mapper::transform).toList();"
            + "   }"
            + " }");

    ParseService parseService = new ParseService(tempDir);
    var cu = parseService.parse(sourceFile).orElseThrow();
    var decl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

    writer.upsertFile(sourceFile);
    writer.upsertPackage("com.example");
    writer.upsertType(sourceFile, "com.example", decl);
    writer.upsertTypeCallEdges("com.example", decl);

    long callCount =
        session
            .run(
                "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {name: 'transform'})"
                    + " WHERE caller.name = 'map'"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, callCount);
    deleteDir(tempDir);
  }

  private static void deleteDir(Path dir) throws IOException {
    try (var walk = Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }

  private static ClassOrInterfaceDeclaration parseDecl(String src) {
    return new JavaParser()
        .parse(src)
        .getResult()
        .flatMap(cu -> cu.findFirst(ClassOrInterfaceDeclaration.class))
        .orElseThrow(
            () -> new IllegalArgumentException("Could not parse declaration from: " + src));
  }

  private static ClassOrInterfaceDeclaration parseDeclResolved(String src) {
    return resolvingParser()
        .parse(src)
        .getResult()
        .flatMap(cu -> cu.findFirst(ClassOrInterfaceDeclaration.class))
        .orElseThrow(
            () -> new IllegalArgumentException("Could not parse declaration from: " + src));
  }

  private static EnumDeclaration parseEnum(String src) {
    return new JavaParser()
        .parse(src)
        .getResult()
        .flatMap(cu -> cu.findFirst(EnumDeclaration.class))
        .orElseThrow(
            () -> new IllegalArgumentException("Could not parse enum declaration from: " + src));
  }

  private static RecordDeclaration parseRecord(String src) {
    return new JavaParser()
        .parse(src)
        .getResult()
        .flatMap(cu -> cu.findFirst(RecordDeclaration.class))
        .orElseThrow(
            () -> new IllegalArgumentException("Could not parse record declaration from: " + src));
  }

  private static JavaParser resolvingParser() {
    ParserConfiguration config = new ParserConfiguration();
    config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    config.setSymbolResolver(new JavaSymbolSolver(new ReflectionTypeSolver()));
    return new JavaParser(config);
  }

  private static EnumDeclaration parseEnumResolved(String src) {
    return resolvingParser()
        .parse(src)
        .getResult()
        .flatMap(cu -> cu.findFirst(EnumDeclaration.class))
        .orElseThrow(
            () -> new IllegalArgumentException("Could not parse enum declaration from: " + src));
  }

  private static RecordDeclaration parseRecordResolved(String src) {
    return resolvingParser()
        .parse(src)
        .getResult()
        .flatMap(cu -> cu.findFirst(RecordDeclaration.class))
        .orElseThrow(
            () -> new IllegalArgumentException("Could not parse record declaration from: " + src));
  }
}
