package io.github.ousatov.tools.memgraph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
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
      return ownerFqn + "." + Labels.INIT + "(" + params + ")";
    } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException _) {
      String params =
          ctor.getParameters().stream()
              .map(p -> p.getType().asString())
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      return ownerFqn + "." + Labels.INIT + "(" + params + ")";
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
    runWithRetry(Cypher.CYPHER_WIPE_NODES, Map.of());
    runWithRetry(Cypher.CYPHER_WIPE_PROJECT, Map.of());
  }

  /** Creates or refreshes the {@code :Project} anchor node. */
  public void upsertProject(Path sourceRoot) {
    runWithRetry(Cypher.CYPHER_UPSERT_PROJECT, Map.of("sourceRoot", sourceRoot.toString()));
  }

  /** Upserts a {@code :File} node and links it to the project anchor. */
  public void upsertFile(Path file) {
    long lastModified;
    try {
      lastModified = Files.getLastModifiedTime(file).toMillis();
    } catch (IOException _) {
      lastModified = -1L;
    }
    runWithRetry(
        Cypher.CYPHER_UPSERT_FILE,
        Map.of(Labels.PATH, file.toString(), "lastModified", lastModified));
  }

  /** Upserts a {@code :Package} node and links it to the project anchor. */
  public void upsertPackage(String pkg) {
    runWithRetry(Cypher.CYPHER_UPSERT_PACKAGE, Map.of(Labels.NAME, pkg));
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
        Cypher.CYPHER_UPSERT_TYPE_TEMPLATE.formatted(Labels.CLASS),
        Map.of(
            Labels.FQN,
            fqn,
            Labels.NAME,
            decl.getNameAsString(),
            Labels.PKG,
            pkg,
            Labels.PATH,
            file.toString(),
            Labels.IS_ABSTRACT,
            false,
            Labels.VISIBILITY,
            decl.getAccessSpecifier().asString(),
            Labels.IS_ENUM,
            true,
            Labels.IS_RECORD,
            false));
    upsertAnnotationsByFqn(fqn, decl);
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
        Cypher.CYPHER_UPSERT_TYPE_TEMPLATE.formatted(Labels.CLASS),
        Map.of(
            Labels.FQN,
            fqn,
            Labels.NAME,
            decl.getNameAsString(),
            Labels.PKG,
            pkg,
            Labels.PATH,
            file.toString(),
            Labels.IS_ABSTRACT,
            false,
            Labels.VISIBILITY,
            decl.getAccessSpecifier().asString(),
            Labels.IS_ENUM,
            false,
            Labels.IS_RECORD,
            true));
    upsertAnnotationsByFqn(fqn, decl);
    decl.getMethods().forEach(m -> upsertMethod(fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(fqn, c));
  }

  /**
   * Upserts an {@code @interface} declaration as an {@code :Annotation} node, including {@code
   * ANNOTATED_WITH} edges for any meta-annotations applied to it.
   */
  public void upsertAnnotation(Path file, String pkg, AnnotationDeclaration decl) {
    String fqn = pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString();
    runWithRetry(
        Cypher.CYPHER_UPSERT_ANNOTATION,
        Map.of(
            Labels.FQN, fqn,
            Labels.NAME, decl.getNameAsString(),
            Labels.PKG, pkg,
            Labels.PATH, file.toString(),
            Labels.VISIBILITY, decl.getAccessSpecifier().asString()));
    upsertAnnotationsByFqn(fqn, decl);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including
   * directly nested types. Call this after all structural upserts for the file are complete so
   * every callee node already exists.
   */
  public void upsertTypeCallEdges(String pkg, ClassOrInterfaceDeclaration decl) {
    upsertTypeCallEdgesInternal(pkg, null, decl);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}. Call this after
   * all structural upserts for the file are complete.
   */
  public void upsertEnumCallEdges(String pkg, EnumDeclaration decl) {
    String fqn = pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString();
    decl.getMethods().forEach(m -> upsertCallEdges(buildSignature(fqn, m), m));
    decl.getConstructors().forEach(c -> upsertCallEdges(buildConstructorSignature(fqn, c), c));
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}. Call this after
   * all structural upserts for the file are complete.
   */
  public void upsertRecordCallEdges(String pkg, RecordDeclaration decl) {
    String fqn = pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString();
    decl.getMethods().forEach(m -> upsertCallEdges(buildSignature(fqn, m), m));
    decl.getConstructors().forEach(c -> upsertCallEdges(buildConstructorSignature(fqn, c), c));
  }

  private void upsertTypeCallEdgesInternal(
      String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn =
        outerFqn != null
            ? outerFqn + "$" + decl.getNameAsString()
            : (pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString());
    decl.getMethods().forEach(m -> upsertCallEdges(buildSignature(fqn, m), m));
    decl.getConstructors().forEach(c -> upsertCallEdges(buildConstructorSignature(fqn, c), c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeCallEdgesInternal(pkg, fqn, nested));
  }

  private void upsertTypeInternal(
      Path file, String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn =
        outerFqn != null
            ? outerFqn + "$" + decl.getNameAsString()
            : (pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString());
    String label = decl.isInterface() ? Labels.INTERFACE : Labels.CLASS;
    runWithRetry(
        Cypher.CYPHER_UPSERT_TYPE_TEMPLATE.formatted(label),
        Map.of(
            Labels.FQN,
            fqn,
            Labels.NAME,
            decl.getNameAsString(),
            Labels.PKG,
            pkg,
            Labels.PATH,
            file.toString(),
            Labels.IS_ABSTRACT,
            decl.isAbstract(),
            Labels.VISIBILITY,
            decl.getAccessSpecifier().asString(),
            Labels.IS_ENUM,
            false,
            Labels.IS_RECORD,
            false));
    upsertAnnotationsByFqn(fqn, decl);
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
        decl.isInterface() ? Cypher.CYPHER_UPSERT_INTERFACE_EXTENDS : Cypher.CYPHER_UPSERT_EXTENDS;
    decl.getExtendedTypes()
        .forEach(
            ext ->
                withResolvedType(
                    ext,
                    parent ->
                        runWithRetry(
                            extendsCypher, Map.of(Labels.CHILD, fqn, Labels.PARENT, parent))));
    decl.getImplementedTypes()
        .forEach(
            impl ->
                withResolvedType(
                    impl,
                    iface ->
                        runWithRetry(
                            Cypher.CYPHER_UPSERT_IMPLEMENTS,
                            Map.of(Labels.CHILD, fqn, Labels.IFACE, iface))));
  }

  private void upsertField(String ownerFqn, FieldDeclaration field) {
    field
        .getVariables()
        .forEach(
            v -> {
              String fqn = ownerFqn + "#" + v.getNameAsString();
              runWithRetry(
                  Cypher.CYPHER_UPSERT_FIELD,
                  Map.of(
                      Labels.FQN,
                      fqn,
                      Labels.NAME,
                      v.getNameAsString(),
                      "type",
                      v.getTypeAsString(),
                      Labels.IS_STATIC,
                      field.isStatic(),
                      Labels.VISIBILITY,
                      field.getAccessSpecifier().asString(),
                      Labels.OWNER,
                      ownerFqn));
              upsertAnnotationsByFqn(fqn, field);
            });
  }

  private void upsertMethod(String ownerFqn, MethodDeclaration method) {
    String signature = buildSignature(ownerFqn, method);
    runWithRetry(
        Cypher.CYPHER_UPSERT_METHOD,
        Map.of(
            Labels.SIG, signature,
            Labels.NAME, method.getNameAsString(),
            Labels.RET, method.getTypeAsString(),
            Labels.IS_STATIC, method.isStatic(),
            Labels.VISIBILITY, method.getAccessSpecifier().asString(),
            Labels.START, method.getBegin().map(p -> p.line).orElse(0),
            Labels.END, method.getEnd().map(p -> p.line).orElse(0),
            Labels.OWNER, ownerFqn));
    upsertAnnotationsBySig(signature, method);
  }

  private void upsertConstructor(String ownerFqn, ConstructorDeclaration ctor) {
    String signature = buildConstructorSignature(ownerFqn, ctor);
    runWithRetry(
        Cypher.CYPHER_UPSERT_METHOD,
        Map.of(
            Labels.SIG,
            signature,
            Labels.NAME,
            Labels.INIT,
            Labels.RET,
            Labels.VOID,
            Labels.IS_STATIC,
            false,
            Labels.VISIBILITY,
            ctor.getAccessSpecifier().asString(),
            Labels.START,
            ctor.getBegin().map(p -> p.line).orElse(0),
            Labels.END,
            ctor.getEnd().map(p -> p.line).orElse(0),
            Labels.OWNER,
            ownerFqn));
    upsertAnnotationsBySig(signature, ctor);
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
                          Cypher.CYPHER_UPSERT_CALL,
                          Map.of(Labels.CALLER, callerSig, Labels.CALLEE, calleeSig));
                    }));
  }

  /**
   * Resolves each annotation on {@code node} and writes an {@code ANNOTATED_WITH} edge from the
   * element identified by {@code ownerFqn}. Falls back to the simple annotation name when the
   * symbol resolver cannot determine the FQN.
   */
  private void upsertAnnotationsByFqn(String ownerFqn, NodeWithAnnotations<?> node) {
    node.getAnnotations()
        .forEach(
            ann -> {
              String annotFqn;
              try {
                annotFqn = ann.resolve().getQualifiedName();
              } catch (UnsolvedSymbolException
                  | UnsupportedOperationException
                  | IllegalStateException _) {
                annotFqn = ann.getNameAsString();
              }
              runWithRetry(
                  Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_FQN,
                  Map.of(Labels.OWNER, ownerFqn, Labels.ANNOT_FQN, annotFqn));
            });
  }

  /**
   * Resolves each annotation on {@code node} and writes an {@code ANNOTATED_WITH} edge from the
   * method identified by {@code sig}. Falls back to the simple annotation name when the symbol
   * resolver cannot determine the FQN.
   */
  private void upsertAnnotationsBySig(String sig, NodeWithAnnotations<?> node) {
    node.getAnnotations()
        .forEach(
            ann -> {
              String annotFqn;
              try {
                annotFqn = ann.resolve().getQualifiedName();
              } catch (UnsolvedSymbolException
                  | UnsupportedOperationException
                  | IllegalStateException _) {
                annotFqn = ann.getNameAsString();
              }
              runWithRetry(
                  Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_SIG,
                  Map.of(Labels.SIG, sig, Labels.ANNOT_FQN, annotFqn));
            });
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
