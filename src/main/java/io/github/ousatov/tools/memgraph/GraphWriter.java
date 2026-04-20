package io.github.ousatov.tools.memgraph;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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
      "MATCH (p:Project {name: $project}) SET p.sourceRoots = [] DETACH DELETE p";
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
            t.isAbstract = $isAbstract
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
            f.isStatic = $isStatic
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
            m.startLine = $start,
            m.endLine = $end
      WITH m
      MATCH (owner {fqn: $owner, project: $project})
      MERGE (owner)-[:DECLARES]->(m)
      """;
  private static final String CYPHER_UPSERT_CALL =
      """
      MERGE (callee:Method {signature: $callee, project: $project})
      WITH callee
      MATCH (caller:Method {signature: $caller, project: $project})
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

  private static String buildSignature(String ownerFqn, MethodDeclaration m) {
    String params =
        m.getParameters().stream()
            .map(p -> p.getType().asString())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    return ownerFqn + "." + m.getNameAsString() + "(" + params + ")";
  }

  /** Swallows symbol-resolution failures — not every type/callee can be resolved. */
  private static void tryRun(Runnable action) {
    try {
      action.run();
    } catch (UnsolvedSymbolException | UnsupportedOperationException _) {
      // External libs or generics we can't resolve — skip silently.
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
    runWithRetry(CYPHER_UPSERT_FILE, Map.of("path", file.toString()));
  }

  /** Upserts a {@code :Package} node and links it to the project anchor. */
  public void upsertPackage(String pkg) {
    runWithRetry(CYPHER_UPSERT_PACKAGE, Map.of("name", pkg));
  }

  /**
   * Upserts a class or interface declaration and all of its members.
   *
   * <p>Also writes inheritance/implementation edges, fields, and methods.
   */
  public void upsertType(Path file, String pkg, ClassOrInterfaceDeclaration decl) {
    String fqn = pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString();
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
            decl.isAbstract()));
    upsertInheritance(fqn, decl);
    decl.getFields().forEach(f -> upsertField(fqn, f));
    decl.getMethods().forEach(m -> upsertMethod(fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(fqn, c));
  }

  private void upsertInheritance(String fqn, ClassOrInterfaceDeclaration decl) {
    decl.getExtendedTypes()
        .forEach(
            ext ->
                withResolvedType(
                    ext,
                    parent ->
                        runWithRetry(
                            CYPHER_UPSERT_EXTENDS, Map.of("child", fqn, "parent", parent))));
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
                      "fqn",
                      fqn,
                      "name",
                      v.getNameAsString(),
                      "type",
                      v.getTypeAsString(),
                      "isStatic",
                      field.isStatic(),
                      "owner",
                      ownerFqn));
            });
  }

  private void upsertMethod(String ownerFqn, MethodDeclaration method) {
    String signature = buildSignature(ownerFqn, method);
    runWithRetry(
        CYPHER_UPSERT_METHOD,
        Map.of(
            "sig",
            signature,
            "name",
            method.getNameAsString(),
            "ret",
            method.getTypeAsString(),
            "isStatic",
            method.isStatic(),
            "start",
            method.getBegin().map(p -> p.line).orElse(0),
            "end",
            method.getEnd().map(p -> p.line).orElse(0),
            "owner",
            ownerFqn));
    upsertCalls(signature, method);
  }

  private void upsertCalls(String callerSig, MethodDeclaration method) {
    List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
    for (MethodCallExpr call : calls) {
      tryRun(
          () -> {
            ResolvedMethodDeclaration resolved = call.resolve();
            String calleeSig = resolved.getQualifiedSignature();
            runWithRetry(CYPHER_UPSERT_CALL, Map.of("caller", callerSig, "callee", calleeSig));
          });
    }
  }

  private static String buildConstructorSignature(String ownerFqn, ConstructorDeclaration ctor) {
    String params =
        ctor.getParameters().stream()
            .map(p -> p.getType().asString())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    return ownerFqn + ".<init>(" + params + ")";
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
            "start",
            ctor.getBegin().map(p -> p.line).orElse(0),
            "end",
            ctor.getEnd().map(p -> p.line).orElse(0),
            "owner",
            ownerFqn));
    upsertConstructorCalls(signature, ctor);
  }

  private void upsertConstructorCalls(String callerSig, ConstructorDeclaration ctor) {
    List<MethodCallExpr> calls = ctor.findAll(MethodCallExpr.class);
    for (MethodCallExpr call : calls) {
      tryRun(
          () -> {
            ResolvedMethodDeclaration resolved = call.resolve();
            String calleeSig = resolved.getQualifiedSignature();
            runWithRetry(CYPHER_UPSERT_CALL, Map.of("caller", callerSig, "callee", calleeSig));
          });
    }
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
        log.debug("Retry attempt {} after conflict: {}", attempt + 1, e.getMessage());
      }
    }
  }
}
