package io.github.ousatov.tools.memgraph.exe;

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
                "MATCH (:Project {name: $name})-[:CONTAINS]->(:Language)"
                    + "-[:CONTAINS]->(c:Code {project: $name})"
                    + " RETURN count(c) AS n",
                Map.of("name", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(SourceLanguage.supported().size(), codeCount);

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

    assertEquals(SourceLanguage.supported().size(), codeCount);

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
                "MATCH (:Project {name: $p})-[:CONTAINS]->(:Language {name: 'Java'})"
                    + "-[:CONTAINS]->(:Code)-[:CONTAINS]->"
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
  void getAllFileLastModifiedExcludesIncompleteJavascriptFiles() throws IOException {
    Path tempFile = Files.createTempFile("app-", ".ts");
    try {
      writer.upsertFile(tempFile, SourceLanguage.JAVASCRIPT);

      Map<String, Long> incompleteCache =
          writer.getAllFileLastModified(List.of(tempFile), SourceLanguage.JAVASCRIPT);
      assertFalse(incompleteCache.containsKey(tempFile.toString()));

      writer.upsertPackage("js.test", SourceLanguage.JAVASCRIPT);
      writer.upsertJavascriptModule(tempFile, "js.test", "js.test.App", "App", "app.ts", 1, 1);

      Map<String, Long> completeCache =
          writer.getAllFileLastModified(List.of(tempFile), SourceLanguage.JAVASCRIPT);
      assertTrue(completeCache.containsKey(tempFile.toString()));
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  void getAllFileLastModifiedKeepsJavaFilesWithoutDefinitions() throws IOException {
    Path tempFile = Files.createTempFile("widget-", ".java");
    try {
      writer.upsertFile(tempFile, SourceLanguage.JAVA);

      Map<String, Long> cache =
          writer.getAllFileLastModified(List.of(tempFile), SourceLanguage.JAVA);

      assertTrue(cache.containsKey(tempFile.toString()));
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  void getAllFileLastModifiedExcludesFilesWithoutJsLanguage() throws IOException {
    Path tempFile = Files.createTempFile("missing-js-language-", ".ts");
    try {
      writer.upsertFile(tempFile, SourceLanguage.JAVASCRIPT);
      session
          .run(
              "MATCH (f:File {project: $p, path: $path}) REMOVE f.language",
              Map.of("p", PROJECT, "path", tempFile.toString()))
          .consume();

      Map<String, Long> cache =
          writer.getAllFileLastModified(List.of(tempFile), SourceLanguage.JAVASCRIPT);

      assertFalse(cache.containsKey(tempFile.toString()));
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
                "MATCH (:Project {name: $p})-[:CONTAINS]->(:Language {name: 'Java'})"
                    + "-[:CONTAINS]->(:Code)-[:CONTAINS]->"
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
  void upsertTypePersistsMethodOwnerMetadata() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl(
            "package com.example;"
                + " public class Widget {"
                + "   public String getName() { return \"widget\"; }"
                + " }");

    writer.upsertType(TEST_FILE, PKG, decl);

    var row =
        session
            .run(
                "MATCH (m:Method {signature: $sig, project: $p})"
                    + " RETURN m.ownerFqn AS ownerFqn, m.ownerDisplayName AS ownerDisplayName",
                Map.of("sig", "com.example.Widget.getName()", "p", PROJECT))
            .single();

    assertEquals("com.example.Widget", row.get("ownerFqn").asString());
    assertEquals("Widget", row.get("ownerDisplayName").asString());
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
  void upsertTypeDoesNotCreateOrphansWhenFileOrPackageAnchorIsMissing() {
    ClassOrInterfaceDeclaration decl = parseDecl("package com.example; public class Widget {}");

    writer.upsertType(TEST_FILE, PKG, decl);

    long count =
        session
            .run(
                "MATCH (c:Class {fqn: $fqn, project: $p}) RETURN count(c) AS n",
                Map.of("fqn", "com.example.Widget", "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(0, count);
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
  void upsertTypeCreatesImplicitDefaultConstructorForClassWithNoCtor() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl = parseDecl("package com.example; public class Widget {}");

    writer.upsertType(TEST_FILE, PKG, decl);

    var row =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(m:Method {name: '<init>'})"
                    + " RETURN count(m) AS n, m.isSynthetic AS syn",
                Map.of("fqn", "com.example.Widget", "p", PROJECT))
            .single();

    assertEquals(1, row.get("n").asLong(), "Implicit default constructor must be synthesized");
    assertTrue(row.get("syn").asBoolean(), "Synthesized constructor must be isSynthetic=true");
  }

  @Test
  void upsertTypeDoesNotSynthesizeConstructorForInterface() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl("package com.example; public interface Runnable { void run(); }");

    writer.upsertType(TEST_FILE, PKG, decl);

    long count =
        session
            .run(
                "MATCH (:Interface {fqn: $fqn, project: $p})-[:DECLARES]->(m:Method {name:"
                    + " '<init>'}) RETURN count(m) AS n",
                Map.of("fqn", "com.example.Runnable", "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(0, count, "Interfaces must not receive a synthesized constructor");
  }

  @Test
  void upsertJavascriptClassSynthesizesConstructorOnlyWhenMissing() {
    Path jsFile = Path.of("/tmp/test-gw/src/app/service.js");
    String pkg = "js.app";
    String fqn = "js.app.service.Service";
    writer.upsertFile(jsFile, SourceLanguage.JAVASCRIPT);
    writer.upsertPackage(pkg, SourceLanguage.JAVASCRIPT);

    writer.upsertJavascriptClass(
        jsFile, pkg, fqn, "Service", "app/service.js", "", false, false, 1, 1);

    var row =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(m:Method {name:"
                    + " '<init>'}) RETURN count(m) AS n, m.isSynthetic AS syn",
                Map.of("fqn", fqn, "p", PROJECT))
            .single();

    assertEquals(1, row.get("n").asLong(), "Implicit JS constructor must be synthesized");
    assertTrue(row.get("syn").asBoolean(), "Synthesized JS constructor must be isSynthetic=true");
  }

  @Test
  void upsertJavascriptClassDoesNotSynthesizeNoArgConstructorWhenDeclared() {
    Path jsFile = Path.of("/tmp/test-gw/src/app/service.js");
    String pkg = "js.app";
    String fqn = "js.app.service.Service";
    writer.upsertFile(jsFile, SourceLanguage.JAVASCRIPT);
    writer.upsertPackage(pkg, SourceLanguage.JAVASCRIPT);

    writer.upsertJavascriptClass(
        jsFile, pkg, fqn, "Service", "app/service.js", "", false, true, 1, 3);
    writer.upsertJavascriptMethod(
        jsFile, fqn, fqn + ".<init>(any)", "<init>", "void", false, 2, 2, "constructor");

    long noArgCtorCount =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(m:Method {signature:"
                    + " $sig}) RETURN count(m) AS n",
                Map.of("fqn", fqn, "p", PROJECT, "sig", fqn + ".<init>()"))
            .single()
            .get("n")
            .asLong();
    long declaredCtorCount =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(m:Method {signature:"
                    + " $sig}) RETURN count(m) AS n",
                Map.of("fqn", fqn, "p", PROJECT, "sig", fqn + ".<init>(any)"))
            .single()
            .get("n")
            .asLong();

    assertEquals(0, noArgCtorCount, "Declared JS constructors must not create a fake no-arg ctor");
    assertEquals(1, declaredCtorCount, "The declared JS constructor must still be ingested");
  }

  @Test
  void upsertJavascriptModuleLinksModuleToFileAndPackage() {
    Path tsFile =
        Path.of(
            "/tmp/test-gw/src/app/translations/components/"
                + "translation-repository-view-pending/"
                + "translation-repository-view-pending.component.spec.ts");
    String pkg = "js.app.translations.components.translation$2d$repository$2d$view$2d$pending";
    String fqn = pkg + ".translation$2d$repository$2d$view$2d$pending$2e$component$2e$spec$2e$ts";
    writer.upsertFile(tsFile, SourceLanguage.JAVASCRIPT);
    writer.upsertPackage(pkg, SourceLanguage.JAVASCRIPT);

    writer.upsertJavascriptModule(
        tsFile,
        pkg,
        fqn,
        "translation_repository_view_pending_component_spec",
        "app/translations/components/translation-repository-view-pending/"
            + "translation-repository-view-pending.component.spec.ts",
        1,
        397);

    var row =
        session
            .run(
                "MATCH (:Package {name: $pkg, project: $p})-[:CONTAINS]->"
                    + "(module:Class {fqn: $fqn, project: $p}) "
                    + "MATCH (:File {path: $path, project: $p})-[:DEFINES]->(module) "
                    + "RETURN count(module) AS n, module.kind AS kind",
                Map.of("pkg", pkg, "fqn", fqn, "path", tsFile.toString(), "p", PROJECT))
            .single();

    assertEquals(1, row.get("n").asLong());
    assertEquals("module", row.get("kind").asString());
  }

  @Test
  void upsertJavascriptModuleDoesNotCreateOrphansWhenAnchorsAreMissing() {
    Path tsFile = Path.of("/tmp/test-gw/src/app/orphan.spec.ts");
    String pkg = "js.app";
    String fqn = "js.app.orphan$2e$spec$2e$ts";

    writer.upsertJavascriptModule(tsFile, pkg, fqn, "orphan_spec", "app/orphan.spec.ts", 1, 1);

    var row =
        session
            .run(
                "OPTIONAL MATCH (module:Class {fqn: $fqn, project: $p}) "
                    + "OPTIONAL MATCH (method:Method {signature: $sig, project: $p}) "
                    + "RETURN count(module) AS modules, count(method) AS methods",
                Map.of("fqn", fqn, "sig", fqn + ".<init>()", "p", PROJECT))
            .single();

    assertEquals(0, row.get("modules").asLong());
    assertEquals(0, row.get("methods").asLong());
  }

  @Test
  void upsertJavascriptMembersDoNotCreateOrphansWhenOwnerIsMissing() {
    Path jsFile = Path.of("/tmp/test-gw/src/app/missing.ts");
    String owner = "js.app.missing$2e$ts.Missing";
    writer.upsertJavascriptField(
        jsFile, owner, owner + "#value", "value", "string", false, "property");
    writer.upsertJavascriptMethod(
        jsFile, owner, owner + ".load()", "load", "void", false, 1, 1, "method");

    var row =
        session
            .run(
                "OPTIONAL MATCH (field:Field {fqn: $fieldFqn, project: $p}) "
                    + "OPTIONAL MATCH (method:Method {signature: $methodSig, project: $p}) "
                    + "RETURN count(field) AS fields, count(method) AS methods",
                Map.of("fieldFqn", owner + "#value", "methodSig", owner + ".load()", "p", PROJECT))
            .single();

    assertEquals(0, row.get("fields").asLong());
    assertEquals(0, row.get("methods").asLong());
  }

  @Test
  void upsertJavascriptMembersDoNotCreateOrphansWhenFileIsMissing() {
    Path jsFile = Path.of("/tmp/test-gw/src/app/missing-file.ts");
    String owner = "js.app.missing$2d$file$2e$ts.MissingFile";
    session
        .run(
            "MERGE (owner:Class {fqn: $owner, project: $p}) "
                + "SET owner.name = 'MissingFile', owner.language = 'js', "
                + "owner.isExternal = false",
            Map.of("owner", owner, "p", PROJECT))
        .consume();

    writer.upsertJavascriptField(
        jsFile, owner, owner + "#value", "value", "string", false, "property");
    writer.upsertJavascriptMethod(
        jsFile, owner, owner + ".load()", "load", "void", false, 1, 1, "method");

    var row =
        session
            .run(
                "OPTIONAL MATCH (field:Field {fqn: $fieldFqn, project: $p}) "
                    + "OPTIONAL MATCH (method:Method {signature: $methodSig, project: $p}) "
                    + "RETURN count(field) AS fields, count(method) AS methods",
                Map.of("fieldFqn", owner + "#value", "methodSig", owner + ".load()", "p", PROJECT))
            .single();

    assertEquals(0, row.get("fields").asLong());
    assertEquals(0, row.get("methods").asLong());
  }

  @Test
  void upsertJavascriptEnumCreatesEnumClassWithoutConstructor() {
    Path tsFile = Path.of("/tmp/test-gw/src/app/status.ts");
    String pkg = "js.app";
    String fqn = "js.app.status$2e$ts.Status";
    writer.upsertFile(tsFile, SourceLanguage.JAVASCRIPT);
    writer.upsertPackage(pkg, SourceLanguage.JAVASCRIPT);

    writer.upsertJavascriptEnum(tsFile, pkg, fqn, "Status", "app/status.ts", 1, 1);

    var row =
        session
            .run(
                "MATCH (c:Class {fqn: $fqn, project: $p}) "
                    + "OPTIONAL MATCH (c)-[:DECLARES]->(m:Method {name: '<init>'}) "
                    + "RETURN c.isEnum AS isEnum, c.kind AS kind, count(m) AS ctors",
                Map.of("fqn", fqn, "p", PROJECT))
            .single();

    assertTrue(row.get("isEnum").asBoolean());
    assertEquals("enum", row.get("kind").asString());
    assertEquals(0, row.get("ctors").asLong());
  }

  @Test
  void upsertJavascriptClassWritesAbstractMetadataAndTypeRelations() {
    Path tsFile = Path.of("/tmp/test-gw/src/app/user.service.ts");
    String pkg = "js.app";
    String fqn = "js.app.user$2e$service$2e$ts.UserService";
    writer.upsertFile(tsFile, SourceLanguage.JAVASCRIPT);
    writer.upsertPackage(pkg, SourceLanguage.JAVASCRIPT);

    writer.upsertJavascriptClass(
        tsFile, pkg, fqn, "UserService", "app/user.service.ts", "angular", true, false, 1, 5);
    writer.upsertJavascriptExtendsClass(fqn, "js.app.base$2e$service$2e$ts.BaseService");
    writer.upsertJavascriptImplements(fqn, "@angular/core.OnInit");

    var row =
        session
            .run(
                "MATCH (c:Class {fqn: $fqn, project: $p}) "
                    + "MATCH (c)-[:EXTENDS]->(parent:Class) "
                    + "MATCH (c)-[:IMPLEMENTS]->(iface:Interface) "
                    + "RETURN c.isAbstract AS isAbstract, c.framework AS framework, "
                    + "parent.fqn AS parent, iface.fqn AS iface",
                Map.of("fqn", fqn, "p", PROJECT))
            .single();

    assertTrue(row.get("isAbstract").asBoolean());
    assertEquals("angular", row.get("framework").asString());
    assertEquals("js.app.base$2e$service$2e$ts.BaseService", row.get("parent").asString());
    assertEquals("@angular/core.OnInit", row.get("iface").asString());
  }

  @Test
  void upsertJavascriptInterfaceWritesMembersAndExtendsRelation() {
    Path tsFile = Path.of("/tmp/test-gw/src/app/repository.interface.ts");
    String pkg = "js.app";
    String fqn = "js.app.repository$2e$interface$2e$ts.Repository";
    writer.upsertFile(tsFile, SourceLanguage.JAVASCRIPT);
    writer.upsertPackage(pkg, SourceLanguage.JAVASCRIPT);

    writer.upsertJavascriptInterface(
        tsFile, pkg, fqn, "Repository", "interface", "app/repository.interface.ts", "");
    writer.upsertJavascriptInterfaceExtends(fqn, "js.app.base$2e$interface$2e$ts.RepositoryBase");
    writer.upsertJavascriptField(
        tsFile,
        fqn,
        fqn + "#pendingItemsCount",
        "pendingItemsCount",
        "number",
        false,
        "interface-property");
    writer.upsertJavascriptMethod(
        tsFile, fqn, fqn + ".save(Repository)", "save", "void", false, 3, 3, "interface-method");

    var row =
        session
            .run(
                "MATCH (i:Interface {fqn: $fqn, project: $p}) "
                    + "MATCH (i)-[:EXTENDS]->(parent:Interface) "
                    + "OPTIONAL MATCH (i)-[:DECLARES]->(f:Field) "
                    + "OPTIONAL MATCH (i)-[:DECLARES]->(m:Method) "
                    + "WITH parent.fqn AS parent, count(DISTINCT f) AS fields, "
                    + "count(DISTINCT m) AS methods "
                    + "RETURN parent, fields, methods",
                Map.of("fqn", fqn, "p", PROJECT))
            .single();

    assertEquals("js.app.base$2e$interface$2e$ts.RepositoryBase", row.get("parent").asString());
    assertEquals(1, row.get("fields").asLong());
    assertEquals(1, row.get("methods").asLong());
  }

  @Test
  void upsertJavascriptEnumWritesMemberFields() {
    Path tsFile = Path.of("/tmp/test-gw/src/app/tabs.enum.ts");
    String pkg = "js.app";
    String fqn = "js.app.tabs$2e$enum$2e$ts.TabsEnum";
    writer.upsertFile(tsFile, SourceLanguage.JAVASCRIPT);
    writer.upsertPackage(pkg, SourceLanguage.JAVASCRIPT);

    writer.upsertJavascriptEnum(tsFile, pkg, fqn, "TabsEnum", "app/tabs.enum.ts", 1, 4);
    writer.upsertJavascriptField(tsFile, fqn, fqn + "#VIEW", "VIEW", fqn, true, "enum-member");
    writer.upsertJavascriptField(
        tsFile, fqn, fqn + "#PENDING", "PENDING", fqn, true, "enum-member");

    List<String> fieldNames =
        session
            .run(
                "MATCH (:Class {fqn: $fqn, project: $p})-[:DECLARES]->(f:Field) "
                    + "RETURN f.name AS name ORDER BY name",
                Map.of("fqn", fqn, "p", PROJECT))
            .list(row -> row.get("name").asString());

    assertEquals(List.of("PENDING", "VIEW"), fieldNames);
  }

  @Test
  void deleteStaleJavascriptDefinitionsForFileRemovesLegacyModuleOwners() {
    Path tsFile = Path.of("/tmp/test-gw/src/app-dir/app.component.ts");
    String currentPkg = "js.app$2d$dir";
    String currentFqn = "js.app$2d$dir.app$2e$component$2e$ts";
    String legacyPkg = "js.app_dir";
    String legacyFqn = "js.app_dir.app_component";
    writer.upsertFile(tsFile, SourceLanguage.JAVASCRIPT);
    writer.upsertPackage(currentPkg, SourceLanguage.JAVASCRIPT);
    writer.upsertPackage(legacyPkg, SourceLanguage.JAVASCRIPT);
    session.run(
        "MERGE (:Package {name: $name, project: $project})",
        Map.of("name", legacyPkg, "project", PROJECT));
    writer.upsertJavascriptModule(
        tsFile, currentPkg, currentFqn, "app_component", "app-dir/app.component.ts", 1, 3);
    writer.upsertJavascriptModule(
        tsFile, legacyPkg, legacyFqn, "app_component", "app-dir/app.component.ts", 1, 3);
    writer.upsertJavascriptClass(
        tsFile,
        legacyPkg,
        legacyFqn + ".AppComponent",
        "AppComponent",
        "app-dir/app.component.ts",
        "angular",
        false,
        false,
        1,
        3);
    writer.upsertJavascriptField(
        tsFile,
        legacyFqn + ".AppComponent",
        legacyFqn + ".AppComponent#title",
        "title",
        "string",
        false,
        "property");
    writer.upsertJavascriptMethod(
        tsFile,
        legacyFqn + ".AppComponent",
        legacyFqn + ".AppComponent.render()",
        "render",
        "void",
        false,
        2,
        2,
        "method");

    writer.deleteStaleJavascriptDefinitionsForFile(tsFile, currentFqn);

    var row =
        session
            .run(
                "MATCH (current:Class {fqn: $currentFqn, project: $p}) "
                    + "WITH count(current) AS currentModules "
                    + "OPTIONAL MATCH (legacyPkg:Package {name: $legacyPkg, project: $p}) "
                    + "RETURN currentModules, count(legacyPkg) AS legacyPackages",
                Map.of("currentFqn", currentFqn, "legacyPkg", legacyPkg, "p", PROJECT))
            .single();
    long legacyNodes =
        session
            .run(
                "MATCH (n {project: $p}) "
                    + "WHERE ((n:Class OR n:Interface OR n:Field) "
                    + "AND n.fqn STARTS WITH $legacyFqn) "
                    + "OR (n:Method AND n.signature STARTS WITH $legacyFqn) "
                    + "RETURN count(n) AS n",
                Map.of("legacyFqn", legacyFqn, "p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, row.get("currentModules").asLong());
    assertEquals(0, row.get("legacyPackages").asLong());
    assertEquals(0, legacyNodes);
  }

  @Test
  void implicitDefaultConstructorCallsEdgePreservedAfterPhantomCleanup() throws IOException {
    Path tempDir = Files.createTempDirectory("implicit-ctor-test");
    Path serviceFile = tempDir.resolve("com/example/Service.java");
    Path clientFile = tempDir.resolve("com/example/Client.java");
    Files.createDirectories(serviceFile.getParent());
    Files.writeString(
        serviceFile, "package com.example; public class Service { public void serve() {} }");
    Files.writeString(
        clientFile,
        "package com.example;"
            + " public class Client {"
            + "   public void run() { new Service().serve(); }"
            + " }");

    ParseService parseService = new ParseService(tempDir);
    for (Path f : List.of(serviceFile, clientFile)) {
      var cu = parseService.parse(f).orElseThrow();
      String pkg = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
      writer.upsertFile(f);
      writer.upsertPackage(pkg);
      cu.findFirst(ClassOrInterfaceDeclaration.class).ifPresent(d -> writer.upsertType(f, pkg, d));
    }
    for (Path f : List.of(serviceFile, clientFile)) {
      var cu = parseService.parse(f).orElseThrow();
      String pkg = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
      cu.findFirst(ClassOrInterfaceDeclaration.class)
          .ifPresent(d -> writer.upsertTypeCallEdges(pkg, d));
    }
    writer.deletePhantomMethods();

    long initEdges =
        session
            .run(
                "MATCH (:Method {name: 'run', project: $p})"
                    + "-[:CALLS]->(m:Method {name: '<init>', project: $p})"
                    + " RETURN count(m) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(
        1, initEdges, "CALLS edge to implicit default constructor must survive phantom cleanup");
    deleteDir(tempDir);
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
                    + " OR n:Annotation OR n:Method OR n:Field OR n:Language)"
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

    assertEquals(List.of("ACTIVE", "INACTIVE", "label"), fieldNames);
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
  void deleteSourceFilePreservesOwnerDefinedByAnotherFile() {
    Path oldFile = Path.of("/tmp/test-gw/src/com/example/OldMoved.java");
    Path newFile = Path.of("/tmp/test-gw/src/com/example/NewMoved.java");
    String ownerFqn = "com.example.Moved";
    String targetSig = ownerFqn + ".target()";
    String callerSig = "com.example.Caller.call()";
    session
        .run(
            "MERGE (oldFile:File {path: $oldPath, project: $p}) "
                + "SET oldFile.language = 'java' "
                + "MERGE (newFile:File {path: $newPath, project: $p}) "
                + "SET newFile.language = 'java' "
                + "MERGE (owner:Class {fqn: $ownerFqn, project: $p}) "
                + "SET owner.name = 'Moved', owner.isExternal = false "
                + "MERGE (target:Method {signature: $targetSig, project: $p}) "
                + "SET target.name = 'target', target.ownerFqn = $ownerFqn, "
                + "target.ownerDisplayName = 'Moved' "
                + "MERGE (caller:Method {signature: $callerSig, project: $p}) "
                + "SET caller.name = 'call', caller.ownerFqn = 'com.example.Caller', "
                + "caller.ownerDisplayName = 'Caller' "
                + "MERGE (oldFile)-[:DEFINES]->(owner) "
                + "MERGE (newFile)-[:DEFINES]->(owner) "
                + "MERGE (owner)-[:DECLARES]->(target) "
                + "MERGE (oldFile)-[:DEFINES]->(target) "
                + "MERGE (newFile)-[:DEFINES]->(target) "
                + "MERGE (caller)-[:CALLS]->(target)",
            Map.of(
                "p",
                PROJECT,
                "oldPath",
                oldFile.toString(),
                "newPath",
                newFile.toString(),
                "ownerFqn",
                ownerFqn,
                "targetSig",
                targetSig,
                "callerSig",
                callerSig))
        .consume();

    writer.deleteSourceFile(oldFile);

    var row =
        session
            .run(
                "MATCH (owner:Class {fqn: $ownerFqn, project: $p}) "
                    + "MATCH (target:Method {signature: $targetSig, project: $p}) "
                    + "MATCH (:Method {signature: $callerSig, project: $p})-[:CALLS]->(target) "
                    + "MATCH (newFile:File {path: $newPath, project: $p})-[:DEFINES]->(owner) "
                    + "OPTIONAL MATCH (oldFile:File {path: $oldPath, project: $p}) "
                    + "RETURN count(oldFile) AS oldFiles, count(newFile) AS newFiles, "
                    + "count(owner) AS owners, count(target) AS methods",
                Map.of(
                    "p",
                    PROJECT,
                    "oldPath",
                    oldFile.toString(),
                    "newPath",
                    newFile.toString(),
                    "ownerFqn",
                    ownerFqn,
                    "targetSig",
                    targetSig,
                    "callerSig",
                    callerSig))
            .single();

    assertEquals(0, row.get("oldFiles").asLong());
    assertEquals(1, row.get("newFiles").asLong());
    assertEquals(1, row.get("owners").asLong());
    assertEquals(1, row.get("methods").asLong());
  }

  @Test
  void deleteSourceFilePreservesPendingCallsForRetainedFileOnSharedOwner() {
    Path oldFile = Path.of("/tmp/test-gw/src/app/old.ts");
    Path retainedFile = Path.of("/tmp/test-gw/src/app/retained.ts");
    String ownerFqn = "js.app.shared.Owner";
    String oldCallerSig = ownerFqn + ".oldCaller()";
    String retainedCallerSig = ownerFqn + ".retainedCaller()";
    session
        .run(
            "MERGE (oldFile:File {path: $oldPath, project: $p}) "
                + "SET oldFile.language = 'js' "
                + "MERGE (retainedFile:File {path: $retainedPath, project: $p}) "
                + "SET retainedFile.language = 'js' "
                + "MERGE (owner:Class {fqn: $ownerFqn, project: $p}) "
                + "SET owner.name = 'Owner', owner.language = 'js', owner.isExternal = false "
                + "MERGE (oldCaller:Method {signature: $oldCallerSig, project: $p}) "
                + "SET oldCaller.name = 'oldCaller', oldCaller.ownerFqn = $ownerFqn, "
                + "oldCaller.ownerDisplayName = 'Owner' "
                + "MERGE (retainedCaller:Method {signature: $retainedCallerSig, project: $p}) "
                + "SET retainedCaller.name = 'retainedCaller', "
                + "retainedCaller.ownerFqn = $ownerFqn, retainedCaller.ownerDisplayName = 'Owner' "
                + "MERGE (oldFile)-[:DEFINES]->(owner) "
                + "MERGE (retainedFile)-[:DEFINES]->(owner) "
                + "MERGE (owner)-[:DECLARES]->(oldCaller) "
                + "MERGE (owner)-[:DECLARES]->(retainedCaller) "
                + "MERGE (oldFile)-[:DEFINES]->(oldCaller) "
                + "MERGE (retainedFile)-[:DEFINES]->(retainedCaller)",
            Map.of(
                "p",
                PROJECT,
                "oldPath",
                oldFile.toString(),
                "retainedPath",
                retainedFile.toString(),
                "ownerFqn",
                ownerFqn,
                "oldCallerSig",
                oldCallerSig,
                "retainedCallerSig",
                retainedCallerSig))
        .consume();
    writer.upsertPendingCallByName(oldCallerSig, "js.app.shared.Helper", "assist");
    writer.upsertPendingCallByName(retainedCallerSig, "js.app.shared.Helper", "assist");

    writer.deleteSourceFile(oldFile);

    var row =
        session
            .run(
                "OPTIONAL MATCH (oldPending:PendingCall {callerSignature: $oldCallerSig, "
                    + "project: $p}) "
                    + "OPTIONAL MATCH (retainedPending:PendingCall {callerSignature: "
                    + "$retainedCallerSig, project: $p}) "
                    + "RETURN count(DISTINCT oldPending) AS oldPending, "
                    + "count(DISTINCT retainedPending) AS retainedPending",
                Map.of(
                    "p",
                    PROJECT,
                    "oldCallerSig",
                    oldCallerSig,
                    "retainedCallerSig",
                    retainedCallerSig))
            .single();

    assertEquals(0, row.get("oldPending").asLong());
    assertEquals(1, row.get("retainedPending").asLong());
  }

  @Test
  void deleteSourceFilePreservesPendingCallForMethodDefinedByAnotherFile() {
    Path oldFile = Path.of("/tmp/test-gw/src/app/old.ts");
    Path retainedFile = Path.of("/tmp/test-gw/src/app/retained.ts");
    String ownerFqn = "js.app.shared.Owner";
    String sharedCallerSig = ownerFqn + ".sharedCaller()";
    session
        .run(
            "MERGE (oldFile:File {path: $oldPath, project: $p}) "
                + "SET oldFile.language = 'js' "
                + "MERGE (retainedFile:File {path: $retainedPath, project: $p}) "
                + "SET retainedFile.language = 'js' "
                + "MERGE (owner:Class {fqn: $ownerFqn, project: $p}) "
                + "SET owner.name = 'Owner', owner.language = 'js', owner.isExternal = false "
                + "MERGE (caller:Method {signature: $sharedCallerSig, project: $p}) "
                + "SET caller.name = 'sharedCaller', caller.ownerFqn = $ownerFqn, "
                + "caller.ownerDisplayName = 'Owner' "
                + "MERGE (oldFile)-[:DEFINES]->(owner) "
                + "MERGE (retainedFile)-[:DEFINES]->(owner) "
                + "MERGE (owner)-[:DECLARES]->(caller) "
                + "MERGE (oldFile)-[:DEFINES]->(caller) "
                + "MERGE (retainedFile)-[:DEFINES]->(caller)",
            Map.of(
                "p",
                PROJECT,
                "oldPath",
                oldFile.toString(),
                "retainedPath",
                retainedFile.toString(),
                "ownerFqn",
                ownerFqn,
                "sharedCallerSig",
                sharedCallerSig))
        .consume();
    writer.upsertPendingCallByName(sharedCallerSig, "js.app.shared.Helper", "assist");

    writer.deleteSourceFile(oldFile);

    var row =
        session
            .run(
                "MATCH (pending:PendingCall {callerSignature: $sharedCallerSig, project: $p}) "
                    + "MATCH (:File {path: $retainedPath, project: $p})-[:DEFINES]->"
                    + "(:Method {signature: $sharedCallerSig, project: $p}) "
                    + "OPTIONAL MATCH (oldFile:File {path: $oldPath, project: $p}) "
                    + "RETURN count(DISTINCT pending) AS pending, "
                    + "count(DISTINCT oldFile) AS oldFiles",
                Map.of(
                    "p",
                    PROJECT,
                    "oldPath",
                    oldFile.toString(),
                    "retainedPath",
                    retainedFile.toString(),
                    "sharedCallerSig",
                    sharedCallerSig))
            .single();

    assertEquals(1, row.get("pending").asLong());
    assertEquals(0, row.get("oldFiles").asLong());
  }

  @Test
  void deleteFilesMissingFromSourceDeletesOnlyMembersOwnedByMissingFiles() {
    Path missingFile = Path.of("/tmp/test-gw/src/app/missing.ts");
    Path retainedFile = Path.of("/tmp/test-gw/src/app/retained.ts");
    String ownerFqn = "js.app.shared.Owner";
    String staleSig = ownerFqn + ".stale()";
    String sharedSig = ownerFqn + ".shared()";
    session
        .run(
            "MERGE (missingFile:File {path: $missingPath, project: $p}) "
                + "SET missingFile.language = 'js' "
                + "MERGE (retainedFile:File {path: $retainedPath, project: $p}) "
                + "SET retainedFile.language = 'js' "
                + "MERGE (owner:Class {fqn: $ownerFqn, project: $p}) "
                + "SET owner.name = 'Owner', owner.language = 'js', owner.isExternal = false "
                + "MERGE (stale:Method {signature: $staleSig, project: $p}) "
                + "SET stale.name = 'stale', stale.ownerFqn = $ownerFqn, "
                + "stale.ownerDisplayName = 'Owner' "
                + "MERGE (shared:Method {signature: $sharedSig, project: $p}) "
                + "SET shared.name = 'shared', shared.ownerFqn = $ownerFqn, "
                + "shared.ownerDisplayName = 'Owner' "
                + "MERGE (missingFile)-[:DEFINES]->(owner) "
                + "MERGE (retainedFile)-[:DEFINES]->(owner) "
                + "MERGE (owner)-[:DECLARES]->(stale) "
                + "MERGE (owner)-[:DECLARES]->(shared) "
                + "MERGE (missingFile)-[:DEFINES]->(stale) "
                + "MERGE (missingFile)-[:DEFINES]->(shared) "
                + "MERGE (retainedFile)-[:DEFINES]->(shared)",
            Map.of(
                "p",
                PROJECT,
                "missingPath",
                missingFile.toString(),
                "retainedPath",
                retainedFile.toString(),
                "ownerFqn",
                ownerFqn,
                "staleSig",
                staleSig,
                "sharedSig",
                sharedSig))
        .consume();

    writer.deleteFilesMissingFromSource(SRC_ROOT, List.of(retainedFile), SourceLanguage.JAVASCRIPT);

    var row =
        session
            .run(
                "OPTIONAL MATCH (stale:Method {signature: $staleSig, project: $p}) "
                    + "OPTIONAL MATCH (shared:Method {signature: $sharedSig, project: $p}) "
                    + "OPTIONAL MATCH (owner:Class {fqn: $ownerFqn, project: $p}) "
                    + "OPTIONAL MATCH (missingFile:File {path: $missingPath, project: $p}) "
                    + "OPTIONAL MATCH (:File {path: $retainedPath, project: $p})"
                    + "-[:DEFINES]->(shared) "
                    + "RETURN count(DISTINCT stale) AS staleMethods, "
                    + "count(DISTINCT shared) AS sharedMethods, count(DISTINCT owner) AS owners, "
                    + "count(DISTINCT missingFile) AS missingFiles",
                Map.of(
                    "p",
                    PROJECT,
                    "missingPath",
                    missingFile.toString(),
                    "retainedPath",
                    retainedFile.toString(),
                    "ownerFqn",
                    ownerFqn,
                    "staleSig",
                    staleSig,
                    "sharedSig",
                    sharedSig))
            .single();

    assertEquals(0, row.get("staleMethods").asLong());
    assertEquals(1, row.get("sharedMethods").asLong());
    assertEquals(1, row.get("owners").asLong());
    assertEquals(0, row.get("missingFiles").asLong());
  }

  @Test
  void deleteFilesMissingFromSourcePreservesPendingCallsForRetainedFileOnSharedOwner() {
    Path missingFile = Path.of("/tmp/test-gw/src/app/missing.ts");
    Path retainedFile = Path.of("/tmp/test-gw/src/app/retained.ts");
    String ownerFqn = "js.app.shared.Owner";
    String missingCallerSig = ownerFqn + ".missingCaller()";
    String retainedCallerSig = ownerFqn + ".retainedCaller()";
    session
        .run(
            "MERGE (missingFile:File {path: $missingPath, project: $p}) "
                + "SET missingFile.language = 'js' "
                + "MERGE (retainedFile:File {path: $retainedPath, project: $p}) "
                + "SET retainedFile.language = 'js' "
                + "MERGE (owner:Class {fqn: $ownerFqn, project: $p}) "
                + "SET owner.name = 'Owner', owner.language = 'js', owner.isExternal = false "
                + "MERGE (missingCaller:Method {signature: $missingCallerSig, project: $p}) "
                + "SET missingCaller.name = 'missingCaller', "
                + "missingCaller.ownerFqn = $ownerFqn, missingCaller.ownerDisplayName = 'Owner' "
                + "MERGE (retainedCaller:Method {signature: $retainedCallerSig, project: $p}) "
                + "SET retainedCaller.name = 'retainedCaller', "
                + "retainedCaller.ownerFqn = $ownerFqn, retainedCaller.ownerDisplayName = 'Owner' "
                + "MERGE (missingFile)-[:DEFINES]->(owner) "
                + "MERGE (retainedFile)-[:DEFINES]->(owner) "
                + "MERGE (owner)-[:DECLARES]->(missingCaller) "
                + "MERGE (owner)-[:DECLARES]->(retainedCaller) "
                + "MERGE (missingFile)-[:DEFINES]->(missingCaller) "
                + "MERGE (retainedFile)-[:DEFINES]->(retainedCaller)",
            Map.of(
                "p",
                PROJECT,
                "missingPath",
                missingFile.toString(),
                "retainedPath",
                retainedFile.toString(),
                "ownerFqn",
                ownerFqn,
                "missingCallerSig",
                missingCallerSig,
                "retainedCallerSig",
                retainedCallerSig))
        .consume();
    writer.upsertPendingCallByName(missingCallerSig, "js.app.shared.Helper", "assist");
    writer.upsertPendingCallByName(retainedCallerSig, "js.app.shared.Helper", "assist");

    writer.deleteFilesMissingFromSource(SRC_ROOT, List.of(retainedFile), SourceLanguage.JAVASCRIPT);

    var row =
        session
            .run(
                "OPTIONAL MATCH (missingPending:PendingCall {callerSignature: "
                    + "$missingCallerSig, project: $p}) "
                    + "OPTIONAL MATCH (retainedPending:PendingCall {callerSignature: "
                    + "$retainedCallerSig, project: $p}) "
                    + "RETURN count(DISTINCT missingPending) AS missingPending, "
                    + "count(DISTINCT retainedPending) AS retainedPending",
                Map.of(
                    "p",
                    PROJECT,
                    "missingCallerSig",
                    missingCallerSig,
                    "retainedCallerSig",
                    retainedCallerSig))
            .single();

    assertEquals(0, row.get("missingPending").asLong());
    assertEquals(1, row.get("retainedPending").asLong());
  }

  @Test
  void deleteFilesMissingFromSourcePreservesPendingCallForMethodDefinedByRetainedFile() {
    Path missingFile = Path.of("/tmp/test-gw/src/app/missing.ts");
    Path retainedFile = Path.of("/tmp/test-gw/src/app/retained.ts");
    String ownerFqn = "js.app.shared.Owner";
    String sharedCallerSig = ownerFqn + ".sharedCaller()";
    session
        .run(
            "MERGE (missingFile:File {path: $missingPath, project: $p}) "
                + "SET missingFile.language = 'js' "
                + "MERGE (retainedFile:File {path: $retainedPath, project: $p}) "
                + "SET retainedFile.language = 'js' "
                + "MERGE (owner:Class {fqn: $ownerFqn, project: $p}) "
                + "SET owner.name = 'Owner', owner.language = 'js', owner.isExternal = false "
                + "MERGE (caller:Method {signature: $sharedCallerSig, project: $p}) "
                + "SET caller.name = 'sharedCaller', caller.ownerFqn = $ownerFqn, "
                + "caller.ownerDisplayName = 'Owner' "
                + "MERGE (missingFile)-[:DEFINES]->(owner) "
                + "MERGE (retainedFile)-[:DEFINES]->(owner) "
                + "MERGE (owner)-[:DECLARES]->(caller) "
                + "MERGE (missingFile)-[:DEFINES]->(caller) "
                + "MERGE (retainedFile)-[:DEFINES]->(caller)",
            Map.of(
                "p",
                PROJECT,
                "missingPath",
                missingFile.toString(),
                "retainedPath",
                retainedFile.toString(),
                "ownerFqn",
                ownerFqn,
                "sharedCallerSig",
                sharedCallerSig))
        .consume();
    writer.upsertPendingCallByName(sharedCallerSig, "js.app.shared.Helper", "assist");

    writer.deleteFilesMissingFromSource(SRC_ROOT, List.of(retainedFile), SourceLanguage.JAVASCRIPT);

    var row =
        session
            .run(
                "MATCH (pending:PendingCall {callerSignature: $sharedCallerSig, project: $p}) "
                    + "MATCH (:File {path: $retainedPath, project: $p})-[:DEFINES]->"
                    + "(:Method {signature: $sharedCallerSig, project: $p}) "
                    + "OPTIONAL MATCH (missingFile:File {path: $missingPath, project: $p}) "
                    + "RETURN count(DISTINCT pending) AS pending, "
                    + "count(DISTINCT missingFile) AS missingFiles",
                Map.of(
                    "p",
                    PROJECT,
                    "missingPath",
                    missingFile.toString(),
                    "retainedPath",
                    retainedFile.toString(),
                    "sharedCallerSig",
                    sharedCallerSig))
            .single();

    assertEquals(1, row.get("pending").asLong());
    assertEquals(0, row.get("missingFiles").asLong());
  }

  @Test
  void deleteFilesMissingFromSourceIgnoresFilesOutsideCurrentSourceRoot() {
    Path currentMissingFile = SRC_ROOT.resolve("app/missing.ts");
    Path currentRetainedFile = SRC_ROOT.resolve("app/retained.ts");
    Path otherRoot = Path.of("/tmp/test-gw-other/src");
    Path otherRootFile = otherRoot.resolve("app/other.ts");
    writer.upsertProject(otherRoot);
    session
        .run(
            "MERGE (currentMissingFile:File {path: $currentMissingPath, project: $p}) "
                + "SET currentMissingFile.language = 'js' "
                + "MERGE (currentRetainedFile:File {path: $currentRetainedPath, project: $p}) "
                + "SET currentRetainedFile.language = 'js' "
                + "MERGE (otherRootFile:File {path: $otherRootPath, project: $p}) "
                + "SET otherRootFile.language = 'js' "
                + "MERGE (currentOwner:Class {fqn: 'js.current.Owner', project: $p}) "
                + "SET currentOwner.name = 'Owner', currentOwner.language = 'js', "
                + "currentOwner.isExternal = false "
                + "MERGE (otherOwner:Class {fqn: 'js.other.Owner', project: $p}) "
                + "SET otherOwner.name = 'Owner', otherOwner.language = 'js', "
                + "otherOwner.isExternal = false "
                + "MERGE (currentMissingFile)-[:DEFINES]->(currentOwner) "
                + "MERGE (otherRootFile)-[:DEFINES]->(otherOwner)",
            Map.of(
                "p",
                PROJECT,
                "currentMissingPath",
                currentMissingFile.toString(),
                "currentRetainedPath",
                currentRetainedFile.toString(),
                "otherRootPath",
                otherRootFile.toString()))
        .consume();

    writer.deleteFilesMissingFromSource(
        SRC_ROOT, List.of(currentRetainedFile), SourceLanguage.JAVASCRIPT);

    var row =
        session
            .run(
                "OPTIONAL MATCH (currentMissingFile:File {path: $currentMissingPath, "
                    + "project: $p}) "
                    + "OPTIONAL MATCH (otherRootFile:File {path: $otherRootPath, project: $p}) "
                    + "OPTIONAL MATCH (otherOwner:Class {fqn: 'js.other.Owner', project: $p}) "
                    + "RETURN count(DISTINCT currentMissingFile) AS currentMissingFiles, "
                    + "count(DISTINCT otherRootFile) AS otherRootFiles, "
                    + "count(DISTINCT otherOwner) AS otherOwners",
                Map.of(
                    "p",
                    PROJECT,
                    "currentMissingPath",
                    currentMissingFile.toString(),
                    "otherRootPath",
                    otherRootFile.toString()))
            .single();

    assertEquals(0, row.get("currentMissingFiles").asLong());
    assertEquals(1, row.get("otherRootFiles").asLong());
    assertEquals(1, row.get("otherOwners").asLong());
  }

  @Test
  void deleteFilesMissingFromSourcePreservesOwnerRetainedByAnotherLanguage() {
    Path missingFile = SRC_ROOT.resolve("app/missing.ts");
    Path retainedFile = SRC_ROOT.resolve("com/example/Owner.java");
    String ownerFqn = "com.example.Owner";
    session
        .run(
            """
            MERGE (missingFile:File {path: $missingPath, project: $p})
            SET missingFile.language = 'js'
            MERGE (retainedFile:File {path: $retainedPath, project: $p})
            SET retainedFile.language = 'java'
            MERGE (owner:Class {fqn: $ownerFqn, project: $p})
            SET owner.name = 'Owner', owner.language = 'java', owner.isExternal = false
            MERGE (missingFile)-[:DEFINES]->(owner)
            MERGE (retainedFile)-[:DEFINES]->(owner)
            """,
            Map.of(
                "p",
                PROJECT,
                "missingPath",
                missingFile.toString(),
                "retainedPath",
                retainedFile.toString(),
                "ownerFqn",
                ownerFqn))
        .consume();

    writer.deleteFilesMissingFromSource(
        SRC_ROOT, List.of(), List.of(retainedFile), SourceLanguage.JAVASCRIPT);

    var row =
        session
            .run(
                """
                OPTIONAL MATCH (missingFile:File {path: $missingPath, project: $p})
                OPTIONAL MATCH (retainedFile:File {path: $retainedPath, project: $p})
                OPTIONAL MATCH (owner:Class {fqn: $ownerFqn, project: $p})
                RETURN count(DISTINCT missingFile) AS missingFiles,
                    count(DISTINCT retainedFile) AS retainedFiles,
                    count(DISTINCT owner) AS owners
                """,
                Map.of(
                    "p",
                    PROJECT,
                    "missingPath",
                    missingFile.toString(),
                    "retainedPath",
                    retainedFile.toString(),
                    "ownerFqn",
                    ownerFqn))
            .single();

    assertEquals(0, row.get("missingFiles").asLong());
    assertEquals(1, row.get("retainedFiles").asLong());
    assertEquals(1, row.get("owners").asLong());
  }

  @Test
  void hasExistingDefinitionsReturnsTrueWhenOnlyClassMatches() {
    String ownerFqn = "com.example.OnlyClass";
    session
        .run(
            "MERGE (owner:Class {fqn: $ownerFqn, project: $p}) "
                + "SET owner.name = 'OnlyClass', owner.language = 'java', owner.isExternal = false",
            Map.of("p", PROJECT, "ownerFqn", ownerFqn))
        .consume();

    SourceFileDefinitions definitions =
        SourceFileDefinitions.of(List.of(ownerFqn), List.of(), List.of(), List.of(), List.of());

    assertTrue(writer.hasExistingDefinitions(definitions));
  }

  @Test
  void deleteStaleDefinitionsForFilePreservesSharedOwnerRelationsFromRetainedFile() {
    Path changedFile = Path.of("/tmp/test-gw/src/app/changed.ts");
    Path retainedFile = Path.of("/tmp/test-gw/src/app/retained.ts");
    String ownerFqn = "js.app.shared.Owner";
    String parentFqn = "js.app.shared.BaseOwner";
    String annotationFqn = "js.app.shared.Decorator";
    session
        .run(
            "MERGE (changedFile:File {path: $changedPath, project: $p}) SET changedFile.language ="
                + " 'js' MERGE (retainedFile:File {path: $retainedPath, project: $p}) SET"
                + " retainedFile.language = 'js' MERGE (owner:Class {fqn: $ownerFqn, project: $p})"
                + " SET owner.name = 'Owner', owner.language = 'js', owner.isExternal = false MERGE"
                + " (parent:Class {fqn: $parentFqn, project: $p}) SET parent.name = 'BaseOwner',"
                + " parent.language = 'js', parent.isExternal = false MERGE (decorator:Annotation"
                + " {fqn: $annotationFqn, project: $p}) SET decorator.name = 'Decorator',"
                + " decorator.language = 'js' MERGE (changedFile)-[:DEFINES]->(owner) MERGE"
                + " (retainedFile)-[:DEFINES]->(owner) MERGE (owner)-[:EXTENDS]->(parent) MERGE"
                + " (owner)-[:ANNOTATED_WITH]->(decorator)",
            Map.of(
                "p",
                PROJECT,
                "changedPath",
                changedFile.toString(),
                "retainedPath",
                retainedFile.toString(),
                "ownerFqn",
                ownerFqn,
                "parentFqn",
                parentFqn,
                "annotationFqn",
                annotationFqn))
        .consume();
    SourceFileDefinitions definitions =
        SourceFileDefinitions.of(List.of(ownerFqn), List.of(), List.of(), List.of(), List.of());

    writer.deleteStaleDefinitionsForFile(changedFile, definitions);

    var row =
        session
            .run(
                "MATCH (owner:Class {fqn: $ownerFqn, project: $p}) "
                    + "OPTIONAL MATCH (owner)-[:EXTENDS]->(parent:Class {fqn: $parentFqn, "
                    + "project: $p}) "
                    + "OPTIONAL MATCH (owner)-[:ANNOTATED_WITH]->"
                    + "(decorator:Annotation {fqn: $annotationFqn, project: $p}) "
                    + "RETURN count(DISTINCT parent) AS parents, "
                    + "count(DISTINCT decorator) AS decorators",
                Map.of(
                    "p",
                    PROJECT,
                    "ownerFqn",
                    ownerFqn,
                    "parentFqn",
                    parentFqn,
                    "annotationFqn",
                    annotationFqn))
            .single();

    assertEquals(1, row.get("parents").asLong());
    assertEquals(1, row.get("decorators").asLong());
  }

  @Test
  void deleteStaleDefinitionsForFilePreservesSharedMemberCallsAndAnnotationsFromRetainedFile() {
    Path changedFile = Path.of("/tmp/test-gw/src/app/changed.ts");
    Path retainedFile = Path.of("/tmp/test-gw/src/app/retained.ts");
    String ownerFqn = "js.app.shared.Owner";
    String callerSig = ownerFqn + ".sharedCaller()";
    String calleeSig = "js.app.shared.Helper.assist()";
    String annotationFqn = "js.app.shared.Trace";
    session
        .run(
            """
            MERGE (changedFile:File {path: $changedPath, project: $p})
            SET changedFile.language = 'js'
            MERGE (retainedFile:File {path: $retainedPath, project: $p})
            SET retainedFile.language = 'js'
            MERGE (owner:Class {fqn: $ownerFqn, project: $p})
            SET owner.name = 'Owner', owner.language = 'js', owner.isExternal = false
            MERGE (caller:Method {signature: $callerSig, project: $p})
            SET caller.name = 'sharedCaller', caller.ownerFqn = $ownerFqn,
                caller.ownerDisplayName = 'Owner'
            MERGE (callee:Method {signature: $calleeSig, project: $p})
            SET callee.name = 'assist', callee.ownerFqn = 'js.app.shared.Helper',
                callee.ownerDisplayName = 'Helper'
            MERGE (trace:Annotation {fqn: $annotationFqn, project: $p})
            SET trace.name = 'Trace', trace.language = 'js'
            MERGE (changedFile)-[:DEFINES]->(owner)
            MERGE (retainedFile)-[:DEFINES]->(owner)
            MERGE (changedFile)-[:DEFINES]->(caller)
            MERGE (retainedFile)-[:DEFINES]->(caller)
            MERGE (owner)-[:DECLARES]->(caller)
            MERGE (caller)-[:CALLS]->(callee)
            MERGE (caller)-[:ANNOTATED_WITH]->(trace)
            """,
            Map.of(
                "p",
                PROJECT,
                "changedPath",
                changedFile.toString(),
                "retainedPath",
                retainedFile.toString(),
                "ownerFqn",
                ownerFqn,
                "callerSig",
                callerSig,
                "calleeSig",
                calleeSig,
                "annotationFqn",
                annotationFqn))
        .consume();
    SourceFileDefinitions definitions =
        SourceFileDefinitions.of(
            List.of(ownerFqn), List.of(), List.of(), List.of(callerSig), List.of());

    writer.deleteStaleDefinitionsForFile(changedFile, definitions);

    var row =
        session
            .run(
                """
                MATCH (caller:Method {signature: $callerSig, project: $p})
                OPTIONAL MATCH (caller)-[:CALLS]->(callee:Method {signature: $calleeSig, project: $p})
                OPTIONAL MATCH (caller)-[:ANNOTATED_WITH]->(trace:Annotation {fqn: $annotationFqn, project: $p})
                RETURN count(DISTINCT callee) AS callees, count(DISTINCT trace) AS annotations
                """,
                Map.of(
                    "p",
                    PROJECT,
                    "callerSig",
                    callerSig,
                    "calleeSig",
                    calleeSig,
                    "annotationFqn",
                    annotationFqn))
            .single();

    assertEquals(1, row.get("callees").asLong());
    assertEquals(1, row.get("annotations").asLong());
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

  @Test
  void constructorCallCreatesCallsEdge() throws IOException {
    Path tempDir = Files.createTempDirectory("ctor-call-test");
    Path sourceFile = tempDir.resolve("com/example/Widget.java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(
        sourceFile,
        "package com.example;"
            + " public class Widget {"
            + "   public Widget() {}"
            + "   public static Widget of() { return new Widget(); }"
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
                "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {name: '<init>'})"
                    + " WHERE caller.name = 'of'"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, callCount);
    deleteDir(tempDir);
  }

  @Test
  void thisDelegationCreatesCallsEdge() throws IOException {
    Path tempDir = Files.createTempDirectory("this-delegation-test");
    Path sourceFile = tempDir.resolve("com/example/Point.java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(
        sourceFile,
        "package com.example;"
            + " public class Point {"
            + "   private final int x;"
            + "   private final int y;"
            + "   public Point(int x, int y) { this.x = x; this.y = y; }"
            + "   public Point(int x) { this(x, 0); }"
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
                "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {name: '<init>'})"
                    + " WHERE caller.name = '<init>'"
                    + " AND caller.signature CONTAINS 'Point.<init>(int)'"
                    + " AND callee.signature CONTAINS 'Point.<init>(int, int)'"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, callCount);
    deleteDir(tempDir);
  }

  @Test
  void scopedStaticCallCreatesCallsEdge() throws IOException {
    Path tempDir = Files.createTempDirectory("scoped-static-test");
    Path helperFile = tempDir.resolve("com/example/Helper.java");
    Path callerFile = tempDir.resolve("com/example/Caller.java");
    Files.createDirectories(helperFile.getParent());
    Files.writeString(
        helperFile,
        "package com.example;"
            + " public class Helper {"
            + "   public static void assist() {}"
            + " }");
    Files.writeString(
        callerFile,
        "package com.example;"
            + " import com.example.Helper;"
            + " public class Caller {"
            + "   public void doWork() { Helper.assist(); }"
            + " }");

    ParseService parseService = new ParseService(tempDir);

    var helperCu = parseService.parse(helperFile).orElseThrow();
    var helperDecl = helperCu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    writer.upsertFile(helperFile);
    writer.upsertPackage("com.example");
    writer.upsertType(helperFile, "com.example", helperDecl);

    var callerCu = parseService.parse(callerFile).orElseThrow();
    var callerDecl = callerCu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    writer.upsertFile(callerFile);
    writer.upsertType(callerFile, "com.example", callerDecl);
    writer.upsertTypeCallEdges("com.example", callerDecl);

    long callCount =
        session
            .run(
                "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {name: 'assist'})"
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
  void unresolvedUnscopedCallFallsBackByOwnerName() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl(
            "package com.example;"
                + " public class Service {"
                + "   private void helper() {}"
                + "   public void doWork() { helper(); }"
                + " }");

    writer.upsertType(TEST_FILE, PKG, decl);
    writer.upsertTypeCallEdges(PKG, decl);

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
  }

  @Test
  void unresolvedUnscopedCallFallsBackToInheritedOwnerMethod() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration base =
        parseDecl(
            "package com.example;"
                + " public class BaseService {"
                + "   public void refreshRepository() {}"
                + " }");
    ClassOrInterfaceDeclaration service =
        parseDecl(
            "package com.example;"
                + " public class RepositoryService extends com.example.BaseService {"
                + "   public void load() { refreshRepository(); }"
                + " }");

    writer.upsertType(TEST_FILE, PKG, base);
    writer.upsertType(TEST_FILE, PKG, service);
    writer.upsertTypeCallEdges(PKG, service);

    long callCount =
        session
            .run(
                "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {project: $p})"
                    + " WHERE caller.name = 'load'"
                    + " AND callee.name = 'refreshRepository'"
                    + " AND callee.ownerFqn = 'com.example.BaseService'"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, callCount);
  }

  @Test
  void unresolvedUnscopedCallFallsBackToNearestInheritedOwnerMethod() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration base =
        parseDecl(
            "package com.example;"
                + " public class BaseService {"
                + "   public void refreshRepository() {}"
                + " }");
    ClassOrInterfaceDeclaration middle =
        parseDecl(
            "package com.example;"
                + " public class MiddleService extends com.example.BaseService {"
                + "   public void refreshRepository() {}"
                + " }");
    ClassOrInterfaceDeclaration service =
        parseDecl(
            "package com.example;"
                + " public class RepositoryService extends com.example.MiddleService {"
                + "   public void load() { refreshRepository(); }"
                + " }");

    writer.upsertType(TEST_FILE, PKG, base);
    writer.upsertType(TEST_FILE, PKG, middle);
    writer.upsertType(TEST_FILE, PKG, service);
    writer.upsertTypeCallEdges(PKG, service);

    var row =
        session
            .run(
                "MATCH (caller:Method {name: 'load', project: $p})-[:CALLS]->"
                    + "(callee:Method {name: 'refreshRepository', project: $p})"
                    + " RETURN count(callee) AS n, collect(callee.ownerFqn) AS owners",
                Map.of("p", PROJECT))
            .single();

    assertEquals(1, row.get("n").asLong());
    assertEquals(List.of("com.example.MiddleService"), row.get("owners").asList());
  }

  @Test
  void pendingCallByNameResolvesAfterCalleeArrives() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration caller =
        parseDecl("package com.example; public class Caller { public void run() {} }");
    ClassOrInterfaceDeclaration helper =
        parseDecl("package com.example; public class Helper { public static void assist() {} }");

    writer.upsertType(TEST_FILE, PKG, caller);
    writer.upsertPendingCallByName("com.example.Caller.run()", "com.example.Helper", "assist");

    long pendingBefore =
        session
            .run("MATCH (p:PendingCall {project: $p}) RETURN count(p) AS n", Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();
    long callsBefore =
        session
            .run(
                "MATCH (:Method {name: 'run', project: $p})"
                    + "-[:CALLS]->(:Method {name: 'assist', project: $p})"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    writer.upsertType(TEST_FILE, PKG, helper);
    writer.resolvePendingCalls();

    long pendingAfter =
        session
            .run("MATCH (p:PendingCall {project: $p}) RETURN count(p) AS n", Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();
    long callsAfter =
        session
            .run(
                "MATCH (:Method {name: 'run', project: $p})"
                    + "-[:CALLS]->(:Method {name: 'assist', project: $p})"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, pendingBefore);
    assertEquals(0, callsBefore);
    assertEquals(0, pendingAfter);
    assertEquals(1, callsAfter);
  }

  @Test
  void pendingCallByNameResolvesThroughInheritedOwnerMethod() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration caller =
        parseDecl("package com.example; public class Caller { public void run() {} }");
    ClassOrInterfaceDeclaration service =
        parseDecl(
            "package com.example;"
                + " public class RepositoryService extends com.example.BaseService {}");
    ClassOrInterfaceDeclaration base =
        parseDecl(
            "package com.example;"
                + " public class BaseService {"
                + "   public void refreshRepository() {}"
                + " }");

    writer.upsertType(TEST_FILE, PKG, caller);
    writer.upsertType(TEST_FILE, PKG, service);
    writer.upsertPendingCallByName(
        "com.example.Caller.run()", "com.example.RepositoryService", "refreshRepository");

    long pendingBefore =
        session
            .run("MATCH (p:PendingCall {project: $p}) RETURN count(p) AS n", Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    writer.upsertType(TEST_FILE, PKG, base);
    writer.resolvePendingCalls();

    var row =
        session
            .run(
                "MATCH (pending:PendingCall {project: $p}) WITH count(pending) AS pendingAfter"
                    + " OPTIONAL MATCH (:Method {name: 'run', project: $p})-[:CALLS]->"
                    + "(callee:Method {name: 'refreshRepository', project: $p}) RETURN"
                    + " pendingAfter, count(callee) AS callsAfter, collect(callee.ownerFqn) AS"
                    + " owners",
                Map.of("p", PROJECT))
            .single();

    assertEquals(1, pendingBefore);
    assertEquals(0, row.get("pendingAfter").asLong());
    assertEquals(1, row.get("callsAfter").asLong());
    assertEquals(List.of("com.example.BaseService"), row.get("owners").asList());
  }

  @Test
  void pendingCallByNameResolvesThroughNearestInheritedOwnerMethod() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration caller =
        parseDecl("package com.example; public class Caller { public void run() {} }");
    ClassOrInterfaceDeclaration service =
        parseDecl(
            "package com.example;"
                + " public class RepositoryService extends com.example.MiddleService {}");
    ClassOrInterfaceDeclaration middle =
        parseDecl(
            "package com.example;"
                + " public class MiddleService extends com.example.BaseService {"
                + "   public void refreshRepository() {}"
                + " }");
    ClassOrInterfaceDeclaration base =
        parseDecl(
            "package com.example;"
                + " public class BaseService {"
                + "   public void refreshRepository() {}"
                + " }");

    writer.upsertType(TEST_FILE, PKG, caller);
    writer.upsertType(TEST_FILE, PKG, service);
    writer.upsertType(TEST_FILE, PKG, middle);
    writer.upsertType(TEST_FILE, PKG, base);
    writer.upsertPendingCallByName(
        "com.example.Caller.run()", "com.example.RepositoryService", "refreshRepository");
    writer.resolvePendingCalls();

    var row =
        session
            .run(
                "MATCH (pending:PendingCall {project: $p}) WITH count(pending) AS pendingAfter"
                    + " OPTIONAL MATCH (:Method {name: 'run', project: $p})-[:CALLS]->"
                    + "(callee:Method {name: 'refreshRepository', project: $p}) RETURN"
                    + " pendingAfter, count(callee) AS callsAfter, collect(callee.ownerFqn) AS"
                    + " owners",
                Map.of("p", PROJECT))
            .single();

    assertEquals(0, row.get("pendingAfter").asLong());
    assertEquals(1, row.get("callsAfter").asLong());
    assertEquals(List.of("com.example.MiddleService"), row.get("owners").asList());
  }

  @Test
  void pendingCallsForFileDeletedBeforeReingest() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration caller =
        parseDecl("package com.example; public class Caller { public void run() {} }");

    writer.upsertType(TEST_FILE, PKG, caller);
    writer.upsertPendingCallByName("com.example.Caller.run()", "com.example.Helper", "assist");

    long pendingBefore =
        session
            .run("MATCH (p:PendingCall {project: $p}) RETURN count(p) AS n", Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    writer.deletePendingCallsForFile(TEST_FILE);

    long pendingAfter =
        session
            .run("MATCH (p:PendingCall {project: $p}) RETURN count(p) AS n", Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, pendingBefore);
    assertEquals(0, pendingAfter);
  }

  @Test
  void unresolvedScopedCallFallsBackByImportedType() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration helper =
        parseDecl("package com.example; public class Helper { public static void assist() {} }");
    ClassOrInterfaceDeclaration caller =
        parseDecl(
            "package com.example;"
                + " import com.example.Helper;"
                + " public class Caller {"
                + "   public void doWork() { Helper.assist(); }"
                + " }");

    writer.upsertType(TEST_FILE, PKG, helper);
    writer.upsertType(TEST_FILE, PKG, caller);
    writer.upsertTypeCallEdges(PKG, caller);

    long callCount =
        session
            .run(
                "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {name: 'assist'})"
                    + " WHERE caller.name = 'doWork'"
                    + " RETURN count(*) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(1, callCount);
  }

  @Test
  void unresolvedConstructorsFallBackByImportedType() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration widget =
        parseDecl("package com.example; public class Widget { public Widget() {} }");
    ClassOrInterfaceDeclaration maker =
        parseDecl(
            "package com.example;"
                + " import com.example.Widget;"
                + " import java.util.function.Supplier;"
                + " public class Maker {"
                + "   public Widget make() { return new Widget(); }"
                + "   public Supplier<Widget> factory() { return Widget::new; }"
                + " }");

    writer.upsertType(TEST_FILE, PKG, widget);
    writer.upsertType(TEST_FILE, PKG, maker);
    writer.upsertTypeCallEdges(PKG, maker);

    long callCount =
        session
            .run(
                "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {name: '<init>'})"
                    + " WHERE caller.name IN ['make', 'factory']"
                    + " RETURN count(callee) AS n",
                Map.of("p", PROJECT))
            .single()
            .get("n")
            .asLong();

    assertEquals(2, callCount);
  }

  @Test
  void unresolvedMethodReferenceFallsBackWithinOwner() {
    writer.upsertFile(TEST_FILE);
    writer.upsertPackage(PKG);
    ClassOrInterfaceDeclaration decl =
        parseDecl(
            "package com.example;"
                + " import java.util.List;"
                + " public class Mapper {"
                + "   private static String transform(String s) { return s; }"
                + "   public List<String> map(List<String> items) {"
                + "     return items.stream().map(Mapper::transform).toList();"
                + "   }"
                + " }");

    writer.upsertType(TEST_FILE, PKG, decl);
    writer.upsertTypeCallEdges(PKG, decl);

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
  }

  private static void deleteDir(Path dir) throws IOException {
    try (var walk = Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                var _ = p.toFile().delete();
              });
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
