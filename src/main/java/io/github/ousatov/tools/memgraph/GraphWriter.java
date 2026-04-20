package io.github.ousatov.tools.memgraph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes all Cypher upsert operations for a single Bolt session.
 *
 * <p>Each instance wraps exactly one {@link Session}. In sequential mode the orchestrator creates
 * one instance; in parallel mode each worker thread creates its own, ensuring no session is shared
 * across threads.
 *
 * @author Oleksii Usatov
 */
public final class GraphWriter {

  private static final Logger log = LoggerFactory.getLogger(GraphWriter.class);

  private static final int MAX_RETRY_ATTEMPTS = 8;
  private static final long INITIAL_BACKOFF_MS = 10L;
  private static final long MAX_BACKOFF_MS = 500L;

  private static final String CYPHER_WIPE_NODES =
      "MATCH (n) WHERE n.project = $project DETACH DELETE n";
  private static final String CYPHER_WIPE_PROJECT =
      "MATCH (p:Project {name: $project}) DETACH DELETE p";
  private static final String CYPHER_UPSERT_PROJECT =
      """
      MERGE (proj:Project {name: $project})
        SET proj.sourceRoots  = CASE
              WHEN $sourceRoot IN coalesce(proj.sourceRoots, [])
              THEN coalesce(proj.sourceRoots, [])
              ELSE coalesce(proj.sourceRoots, []) + $sourceRoot
            END,
            proj.lastIngested = timestamp()
      """;
  private static final String CYPHER_UPSERT_FILE =
      """
      MERGE (f:File {path: $path, project: $project})
        SET f.lastModified = $lastModified
      WITH f
      MATCH (proj:Project {name: $project})
      MERGE (proj)-[:CONTAINS]->(f)
      """;
  private static final String CYPHER_UPSERT_PACKAGE =
      """
      MERGE (p:Package {name: $name, project: $project})
      WITH p
      MATCH (proj:Project {name: $project})
      MERGE (proj)-[:CONTAINS]->(p)
      """;

  /**
   * Template for class/interface upsert — {@code %s} is replaced with {@code Class} or {@code
   * Interface} at call time.
   */
  private static final String CYPHER_UPSERT_TYPE_TEMPLATE =
      """
      MERGE (t:%s {fqn: $fqn, project: $project})
        SET t.name = $name,
            t.packageName = $pkg,
            t.isAbstract = $isAbstract,
            t.visibility = $visibility,
            t.isEnum = $isEnum,
            t.isRecord = $isRecord
      WITH t
      MATCH (p:Package {name: $pkg, project: $project})
      MERGE (p)-[:CONTAINS]->(t)
      WITH t
      MATCH (f:File {path: $path, project: $project})
      MERGE (f)-[:DEFINES]->(t)
      """;

  private static final String CYPHER_UPSERT_EXTENDS =
      """
      MERGE (parent:Class {fqn: $parent, project: $project})
      WITH parent
      MATCH (child {fqn: $child, project: $project})
      MERGE (child)-[:EXTENDS]->(parent)
      """;

  /** Used when an interface extends another interface — parent must be {@code :Interface}. */
  private static final String CYPHER_UPSERT_INTERFACE_EXTENDS =
      """
      MERGE (parent:Interface {fqn: $parent, project: $project})
      WITH parent
      MATCH (child {fqn: $child, project: $project})
      MERGE (child)-[:EXTENDS]->(parent)
      """;

  private static final String CYPHER_UPSERT_IMPLEMENTS =
      """
      MERGE (i:Interface {fqn: $iface, project: $project})
      WITH i
      MATCH (c:Class {fqn: $child, project: $project})
      MERGE (c)-[:IMPLEMENTS]->(i)
      """;
  private static final String CYPHER_UPSERT_FIELD =
      """
      MERGE (f:Field {fqn: $fqn, project: $project})
        SET f.name = $name,
            f.type = $type,
            f.isStatic = $isStatic,
            f.visibility = $visibility
      WITH f
      MATCH (owner {fqn: $owner, project: $project})
      MERGE (owner)-[:DECLARES]->(f)
      """;
  private static final String CYPHER_UPSERT_METHOD =
      """
      MERGE (m:Method {signature: $sig, project: $project})
        SET m.name = $name,
            m.returnType = $ret,
            m.isStatic = $isStatic,
            m.visibility = $visibility,
            m.startLine = $start,
            m.endLine = $end
      WITH m
      MATCH (owner {fqn: $owner, project: $project})
      MERGE (owner)-[:DECLARES]->(m)
      """;

  /**
   * Callee is matched (not merged) so external library methods are never created as project-scoped
   * phantom nodes. Cross-file in-project calls missed on the first pass are filled in by a
   * subsequent wipe-less re-ingestion.
   */
  private static final String CYPHER_UPSERT_CALL =
      """
      MATCH (caller:Method {signature: $caller, project: $project})
      MATCH (callee:Method {signature: $callee, project: $project})
      MERGE (caller)-[:CALLS]->(callee)
      """;

  private final Session session;
  private final String project;

  /**
   * @param session Bolt session — must not be shared with other threads
   * @param project project name used to scope all Cypher operations
   */
  public GraphWriter(Session session, String project) {
    this.session = session;
    this.project = project;
  }

  private static boolean isRetryable(RuntimeException e) {
    String msg = e.getMessage() == null ? "" : e.getMessage();
    return msg.contains("conflicting transactions")
        || msg.contains("deadlock")
        || msg.contains("SerializationError");
  }

  /**
   * Resolves a class/interface reference to its FQN. Returns empty for unresolvable types (e.g.
   * generics, missing classpath entries).
   */
  private static Optional<String> resolveQualifiedName(ClassOrInterfaceType type) {
    try {
      ResolvedReferenceType resolved = type.resolve().asReferenceType();
      return resolved.getTypeDeclaration().map(ResolvedTypeDeclaration::getQualifiedName);
    } catch (UnsolvedSymbolException | UnsupportedOperationException _) {
      return Optional.empty();
    } catch (RuntimeException e) {
      throw new ProcessingException("Unexpected resolution failure", e);
    }
  }

  /**
   * Builds the method signature using symbol resolution when available, falling back to simple type
   * names. Both paths produce the same format as {@code call.resolve().getQualifiedSignature()}, so
   * CALLS edges connect correctly.
   */
  private static String buildSignature(String ownerFqn, MethodDeclaration m) {
    try {
      return m.resolve().getQualifiedSignature();
    } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException _) {
      String params =
          m.getParameters().stream()
              .map(p -> p.getType().asString())
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      return ownerFqn + "." + m.getNameAsString() + "(" + params + ")";
    }
  }

  /**
   * Builds the constructor signature using symbol resolution for FQN parameter types, falling back
   * to simple type names. Uses the {@code <init>} convention for consistent identification.
   */
  private static String buildConstructorSignature(String ownerFqn, ConstructorDeclaration ctor) {
    try {
      String resolved = ctor.resolve().getQualifiedSignature();
      // "com.example.Widget(java.lang.String, int)" → extract params
      int parenIdx = resolved.indexOf('(');
      String params = resolved.substring(parenIdx + 1, resolved.length() - 1);
      return ownerFqn + ".<init>(" + params + ")";
    } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException _) {
      String params =
          ctor.getParameters().stream()
              .map(p -> p.getType().asString())
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      return ownerFqn + ".<init>(" + params + ")";
    }
  }

  /** Swallows symbol-resolution failures — not every type/callee can be resolved. */
  private static void tryRun(Runnable action) {
    try {
      action.run();
    } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException _) {
      // External libs, generics, or unconfigured resolver — skip silently.
    }
  }

  /** Deletes all project-scoped nodes, then deletes the {@code :Project} anchor. */
  public void wipe() {
    runWithRetry(CYPHER_WIPE_NODES, Map.of());
    runWithRetry(CYPHER_WIPE_PROJECT, Map.of());
  }

  /** Creates or refreshes the {@code :Project} anchor node. */
  public void upsertProject(Path sourceRoot) {
    runWithRetry(CYPHER_UPSERT_PROJECT, Map.of("sourceRoot", sourceRoot.toString()));
  }

  /** Upserts a {@code :File} node and links it to the project anchor. */
  public void upsertFile(Path file) {
    long lastModified;
    try {
      lastModified = Files.getLastModifiedTime(file).toMillis();
    } catch (IOException _) {
      lastModified = -1L;
    }
    runWithRetry(CYPHER_UPSERT_FILE, Map.of("path", file.toString(), "lastModified", lastModified));
  }

  /** Upserts a {@code :Package} node and links it to the project anchor. */
  public void upsertPackage(String pkg) {
    runWithRetry(CYPHER_UPSERT_PACKAGE, Map.of("name", pkg));
  }

  /**
   * Upserts a class or interface declaration and all of its members, including directly nested
   * types with their correct {@code $}-separated FQN.
   */
  public void upsertType(Path file, String pkg, ClassOrInterfaceDeclaration decl) {
    upsertTypeInternal(file, pkg, null, decl);
  }

  /**
   * Upserts an enum declaration as a {@code :Class} with {@code isEnum = true}, including its
   * methods and constructors.
   */
  public void upsertEnum(Path file, String pkg, EnumDeclaration decl) {
    String fqn = pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString();
    runWithRetry(
        CYPHER_UPSERT_TYPE_TEMPLATE.formatted("Class"),
        Map.of(
            "fqn",
            fqn,
            "name",
            decl.getNameAsString(),
            "pkg",
            pkg,
            "path",
            file.toString(),
            "isAbstract",
            false,
            "visibility",
            decl.getAccessSpecifier().asString(),
            "isEnum",
            true,
            "isRecord",
            false));
    decl.getMethods().forEach(m -> upsertMethod(fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(fqn, c));
  }

  /**
   * Upserts a record declaration as a {@code :Class} with {@code isRecord = true}, including its
   * methods and constructors.
   */
  public void upsertRecord(Path file, String pkg, RecordDeclaration decl) {
    String fqn = pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString();
    runWithRetry(
        CYPHER_UPSERT_TYPE_TEMPLATE.formatted("Class"),
        Map.of(
            "fqn",
            fqn,
            "name",
            decl.getNameAsString(),
            "pkg",
            pkg,
            "path",
            file.toString(),
            "isAbstract",
            false,
            "visibility",
            decl.getAccessSpecifier().asString(),
            "isEnum",
            false,
            "isRecord",
            true));
    decl.getMethods().forEach(m -> upsertMethod(fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(fqn, c));
  }

  private void upsertTypeInternal(
      Path file, String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn =
        outerFqn != null
            ? outerFqn + "$" + decl.getNameAsString()
            : (pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString());
    String label = decl.isInterface() ? "Interface" : "Class";
    runWithRetry(
        CYPHER_UPSERT_TYPE_TEMPLATE.formatted(label),
        Map.of(
            "fqn",
            fqn,
            "name",
            decl.getNameAsString(),
            "pkg",
            pkg,
            "path",
            file.toString(),
            "isAbstract",
            decl.isAbstract(),
            "visibility",
            decl.getAccessSpecifier().asString(),
            "isEnum",
            false,
            "isRecord",
            false));
    upsertInheritance(fqn, decl);
    decl.getFields().forEach(f -> upsertField(fqn, f));
    decl.getMethods().forEach(m -> upsertMethod(fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(fqn, c));
    // Recurse into directly nested class/interface declarations with correct FQN.
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeInternal(file, pkg, fqn, nested));
  }

  private void upsertInheritance(String fqn, ClassOrInterfaceDeclaration decl) {
    String extendsCypher =
        decl.isInterface() ? CYPHER_UPSERT_INTERFACE_EXTENDS : CYPHER_UPSERT_EXTENDS;
    decl.getExtendedTypes()
        .forEach(
            ext ->
                withResolvedType(
                    ext,
                    parent -> runWithRetry(extendsCypher, Map.of("child", fqn, "parent", parent))));
    decl.getImplementedTypes()
        .forEach(
            impl ->
                withResolvedType(
                    impl,
                    iface ->
                        runWithRetry(
                            CYPHER_UPSERT_IMPLEMENTS, Map.of("child", fqn, "iface", iface))));
  }

  private void upsertField(String ownerFqn, FieldDeclaration field) {
    field
        .getVariables()
        .forEach(
            v -> {
              String fqn = ownerFqn + "#" + v.getNameAsString();
              runWithRetry(
                  CYPHER_UPSERT_FIELD,
                  Map.of(
                      "fqn", fqn,
                      "name", v.getNameAsString(),
                      "type", v.getTypeAsString(),
                      "isStatic", field.isStatic(),
                      "visibility", field.getAccessSpecifier().asString(),
                      "owner", ownerFqn));
            });
  }

  private void upsertMethod(String ownerFqn, MethodDeclaration method) {
    String signature = buildSignature(ownerFqn, method);
    runWithRetry(
        CYPHER_UPSERT_METHOD,
        Map.of(
            "sig", signature,
            "name", method.getNameAsString(),
            "ret", method.getTypeAsString(),
            "isStatic", method.isStatic(),
            "visibility", method.getAccessSpecifier().asString(),
            "start", method.getBegin().map(p -> p.line).orElse(0),
            "end", method.getEnd().map(p -> p.line).orElse(0),
            "owner", ownerFqn));
    upsertCallEdges(signature, method);
  }

  private void upsertConstructor(String ownerFqn, ConstructorDeclaration ctor) {
    String signature = buildConstructorSignature(ownerFqn, ctor);
    runWithRetry(
        CYPHER_UPSERT_METHOD,
        Map.of(
            "sig",
            signature,
            "name",
            "<init>",
            "ret",
            "void",
            "isStatic",
            false,
            "visibility",
            ctor.getAccessSpecifier().asString(),
            "start",
            ctor.getBegin().map(p -> p.line).orElse(0),
            "end",
            ctor.getEnd().map(p -> p.line).orElse(0),
            "owner",
            ownerFqn));
    upsertCallEdges(signature, ctor);
  }

  /**
   * Finds all method call expressions inside {@code bodyNode}, resolves each callee, and writes a
   * {@code CALLS} edge. Replaces the former {@code upsertCalls} and {@code upsertConstructorCalls}
   * pair.
   */
  private void upsertCallEdges(String callerSig, Node bodyNode) {
    bodyNode
        .findAll(MethodCallExpr.class)
        .forEach(
            call ->
                tryRun(
                    () -> {
                      ResolvedMethodDeclaration resolved = call.resolve();
                      String calleeSig = resolved.getQualifiedSignature();
                      runWithRetry(
                          CYPHER_UPSERT_CALL, Map.of("caller", callerSig, "callee", calleeSig));
                    }));
  }

  /** Resolves {@code type} and invokes {@code action} with the FQN; silently skips on failure. */
  private void withResolvedType(ClassOrInterfaceType type, Consumer<String> action) {
    tryRun(() -> resolveQualifiedName(type).ifPresent(action));
  }

  /**
   * Runs {@code cypher} with retry-on-conflict. Auto-injects the {@code project} parameter so
   * callers do not need to include it in {@code params}.
   */
  private void runWithRetry(String cypher, Map<String, Object> params) {
    Map<String, Object> allParams = new HashMap<>(params);
    allParams.put("project", project);
    long backoffMs = INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
      try {
        session.run(cypher, allParams).consume();
        return;
      } catch (RuntimeException e) {
        if (!isRetryable(e) || attempt == MAX_RETRY_ATTEMPTS) {
          if (isRetryable(e)) {
            throw new ProcessingException(
                "Cypher failed after " + MAX_RETRY_ATTEMPTS + " attempts: " + cypher, e);
          }
          throw e;
        }
        try {
          long jitter = (long) (backoffMs * Math.random() * 0.5);
          Thread.sleep(backoffMs + jitter);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new ProcessingException("Interrupted during retry", ie);
        }
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        log.debug("Conflict on attempt {}; will retry: {}", attempt, e.getMessage());
      }
    }
  }
}
