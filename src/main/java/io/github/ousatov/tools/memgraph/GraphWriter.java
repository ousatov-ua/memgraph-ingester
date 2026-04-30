package io.github.ousatov.tools.memgraph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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
  private static final int WIPE_BATCH_SIZE = 10_000;

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

  static boolean isRetryable(RuntimeException e) {
    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    return msg.contains("conflicting transactions")
        || msg.contains("deadlock")
        || msg.contains("serializationerror")
        || msg.contains("unique constraint violation");
  }

  /**
   * Resolves a class/interface reference to its FQN. Returns empty for unresolvable types (e.g.
   * generics, missing classpath entries).
   */
  private static Optional<String> resolveQualifiedName(ClassOrInterfaceType type) {
    try {
      ResolvedReferenceType resolved = type.resolve().asReferenceType();
      return resolved.getTypeDeclaration().map(ResolvedTypeDeclaration::getQualifiedName);
    } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException _) {
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
              .collect(Collectors.joining(", "));
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
              .collect(Collectors.joining(", "));
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

  /** Constructs a fully qualified name from {@code pkg} and {@code simpleName}. */
  private static String buildFqn(String pkg, String simpleName) {
    return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
  }

  /** Deletes the project-scoped {@code :Code} graph in batches, keeping the {@code :Project}. */
  public void wipe() {
    long deleted;
    do {
      deleted =
          runCountWithRetry(
              Cypher.CYPHER_WIPE_PROJECT_CODE_BATCH, "batchSize", WIPE_BATCH_SIZE, "deleted");
    } while (deleted > 0);
  }

  /** Deletes the project-scoped {@code :Memory} graph while keeping the {@code :Project} anchor. */
  public void wipeMemories() {
    runWithRetry(Cypher.CYPHER_WIPE_PROJECT_MEMORIES, Map.of());
  }

  /** Refreshes {@code :CodeRef} resolution edges to the current project-scoped code graph. */
  public void resolveCodeRefs() {
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_CODE, Map.of());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_PACKAGE, Map.of());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_FILE, Map.of());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_CLASS, Map.of());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_INTERFACE, Map.of());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_ANNOTATION, Map.of());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_METHOD, Map.of());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_FIELD, Map.of());
  }

  /**
   * Returns the stored {@code lastModified} epoch-millis for the given file path, or {@code -1} if
   * the file has no graph node.
   */
  public long getFileLastModified(Path file) {
    Map<String, Object> params = Map.of(Params.PATH, file.toString(), Labels.PROJECT, project);
    try {
      var result = session.run(Cypher.CYPHER_GET_FILE_LAST_MODIFIED, params);
      if (!result.hasNext()) {
        return -1L;
      }
      var value = result.single().get("lastModified");
      return value.isNull() ? -1L : value.asLong();
    } catch (RuntimeException e) {
      log.debug("Could not fetch lastModified for {}: {}", file, e.getMessage());
      return -1L;
    }
  }

  /**
   * Batch-fetches stored {@code lastModified} values for all given paths in one query. Only files
   * already present in the graph are included in the returned map; absent files are omitted.
   *
   * @param files list of source paths to check
   * @return map of {@code path.toString()} → stored epoch-millis
   */
  public Map<String, Long> getAllFileLastModified(List<Path> files) {
    List<String> paths = files.stream().map(Path::toString).toList();
    Map<String, Object> params = Map.of("paths", paths, Labels.PROJECT, project);
    try {
      var result = session.run(Cypher.CYPHER_GET_FILES_LAST_MODIFIED, params);
      Map<String, Long> mtimes = new HashMap<>(files.size() * 2);
      while (result.hasNext()) {
        var record = result.next();
        String path = record.get("path").asString(null);
        var value = record.get("lastModified");
        if (path != null && !value.isNull()) {
          mtimes.put(path, value.asLong());
        }
      }
      return mtimes;
    } catch (RuntimeException e) {
      log.debug("Could not batch-fetch lastModified values: {}", e.getMessage());
      return Map.of();
    }
  }

  /** Creates or refreshes the {@code :Project -> :Code} and {@code :Project -> :Memory} anchors. */
  public void upsertProject(Path sourceRoot) {
    runWithRetry(Cypher.CYPHER_UPSERT_PROJECT, Map.of("sourceRoot", sourceRoot.toString()));
  }

  /** Upserts a {@code :File} node and links it to the code anchor. */
  public void upsertFile(Path file) {
    long lastModified;
    try {
      lastModified = Files.getLastModifiedTime(file).toMillis();
    } catch (IOException _) {
      lastModified = -1L;
    }
    runWithRetry(
        Cypher.CYPHER_UPSERT_FILE,
        Map.of(Params.PATH, file.toString(), "lastModified", lastModified));
  }

  /** Upserts a {@code :Package} node and links it to the code anchor. */
  public void upsertPackage(String pkg) {
    runWithRetry(Cypher.CYPHER_UPSERT_PACKAGE, Map.of(Params.NAME, pkg));
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
   * fields, methods, constructors, implemented interfaces, and nested types.
   */
  public void upsertEnum(Path file, String pkg, EnumDeclaration decl) {
    String fqn = buildFqn(pkg, decl.getNameAsString());
    runWithRetry(
        Cypher.CYPHER_UPSERT_CLASS,
        Map.of(
            Params.FQN,
            fqn,
            Params.NAME,
            decl.getNameAsString(),
            Params.PKG,
            pkg,
            Params.PATH,
            file.toString(),
            Params.IS_ABSTRACT,
            false,
            Params.VISIBILITY,
            decl.getAccessSpecifier().asString(),
            Params.IS_ENUM,
            true,
            Params.IS_RECORD,
            false));
    upsertAnnotationsByFqn(fqn, decl);
    upsertImplementedTypes(fqn, decl);
    decl.getFields().forEach(f -> upsertField(fqn, f));
    decl.getMethods().forEach(m -> upsertMethod(fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(fqn, c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeInternal(file, pkg, fqn, nested));
  }

  /**
   * Upserts a record declaration as a {@code :Class} with {@code isRecord = true}, including its
   * fields, methods, constructors, implemented interfaces, and nested types.
   */
  public void upsertRecord(Path file, String pkg, RecordDeclaration decl) {
    String fqn = buildFqn(pkg, decl.getNameAsString());
    runWithRetry(
        Cypher.CYPHER_UPSERT_CLASS,
        Map.of(
            Params.FQN,
            fqn,
            Params.NAME,
            decl.getNameAsString(),
            Params.PKG,
            pkg,
            Params.PATH,
            file.toString(),
            Params.IS_ABSTRACT,
            false,
            Params.VISIBILITY,
            decl.getAccessSpecifier().asString(),
            Params.IS_ENUM,
            false,
            Params.IS_RECORD,
            true));
    upsertAnnotationsByFqn(fqn, decl);
    upsertImplementedTypes(fqn, decl);
    decl.getFields().forEach(f -> upsertField(fqn, f));
    upsertRecordComponents(fqn, decl);
    decl.getMethods().forEach(m -> upsertMethod(fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(fqn, c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeInternal(file, pkg, fqn, nested));
  }

  /**
   * Upserts an {@code @interface} declaration as an {@code :Annotation} node, including {@code
   * ANNOTATED_WITH} edges for any meta-annotations applied to it.
   */
  public void upsertAnnotation(Path file, String pkg, AnnotationDeclaration decl) {
    String fqn = buildFqn(pkg, decl.getNameAsString());
    runWithRetry(
        Cypher.CYPHER_UPSERT_ANNOTATION,
        Map.of(
            Params.FQN,
            fqn,
            Params.NAME,
            decl.getNameAsString(),
            Params.PKG,
            pkg,
            Params.PATH,
            file.toString(),
            Params.VISIBILITY,
            decl.getAccessSpecifier().asString()));
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
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertEnumCallEdges(String pkg, EnumDeclaration decl) {
    String fqn = buildFqn(pkg, decl.getNameAsString());
    decl.getMethods().forEach(m -> upsertCallEdges(buildSignature(fqn, m), m));
    decl.getConstructors().forEach(c -> upsertCallEdges(buildConstructorSignature(fqn, c), c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeCallEdgesInternal(pkg, fqn, nested));
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertRecordCallEdges(String pkg, RecordDeclaration decl) {
    String fqn = buildFqn(pkg, decl.getNameAsString());
    decl.getMethods().forEach(m -> upsertCallEdges(buildSignature(fqn, m), m));
    decl.getConstructors().forEach(c -> upsertCallEdges(buildConstructorSignature(fqn, c), c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeCallEdgesInternal(pkg, fqn, nested));
  }

  private void upsertTypeCallEdgesInternal(
      String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn =
        outerFqn != null ? outerFqn + "$" + decl.getNameAsString() : genDeclName(pkg, decl);
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
        outerFqn != null ? outerFqn + "$" + decl.getNameAsString() : genDeclName(pkg, decl);
    if (decl.isInterface()) {
      runWithRetry(
          Cypher.CYPHER_UPSERT_INTERFACE,
          Map.of(
              Params.FQN,
              fqn,
              Params.NAME,
              decl.getNameAsString(),
              Params.PKG,
              pkg,
              Params.PATH,
              file.toString(),
              Params.IS_ABSTRACT,
              decl.isAbstract(),
              Params.VISIBILITY,
              decl.getAccessSpecifier().asString()));
    } else {
      runWithRetry(
          Cypher.CYPHER_UPSERT_CLASS,
          Map.of(
              Params.FQN,
              fqn,
              Params.NAME,
              decl.getNameAsString(),
              Params.PKG,
              pkg,
              Params.PATH,
              file.toString(),
              Params.IS_ABSTRACT,
              decl.isAbstract(),
              Params.VISIBILITY,
              decl.getAccessSpecifier().asString(),
              Params.IS_ENUM,
              false,
              Params.IS_RECORD,
              false));
    }
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

  private String genDeclName(String pkg, ClassOrInterfaceDeclaration decl) {
    return buildFqn(pkg, decl.getNameAsString());
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
                            extendsCypher, Map.of(Params.CHILD, fqn, Params.PARENT, parent))));
    decl.getImplementedTypes()
        .forEach(
            impl ->
                withResolvedType(
                    impl,
                    iface ->
                        runWithRetry(
                            Cypher.CYPHER_UPSERT_IMPLEMENTS,
                            Map.of(Params.CHILD, fqn, Params.IFACE, iface))));
  }

  /** Writes {@code IMPLEMENTS} edges for enums and records that implement interfaces. */
  private void upsertImplementedTypes(String fqn, NodeWithImplements<?> decl) {
    decl.getImplementedTypes()
        .forEach(
            impl ->
                withResolvedType(
                    impl,
                    iface ->
                        runWithRetry(
                            Cypher.CYPHER_UPSERT_IMPLEMENTS,
                            Map.of(Params.CHILD, fqn, Params.IFACE, iface))));
  }

  /** Upserts record components (parameters) as {@code :Field} nodes. */
  private void upsertRecordComponents(String ownerFqn, RecordDeclaration decl) {
    for (Parameter param : decl.getParameters()) {
      String fqn = ownerFqn + "#" + param.getNameAsString();
      runWithRetry(
          Cypher.CYPHER_UPSERT_FIELD,
          Map.of(
              Params.FQN,
              fqn,
              Params.NAME,
              param.getNameAsString(),
              "type",
              param.getTypeAsString(),
              Params.IS_STATIC,
              false,
              Params.VISIBILITY,
              "private",
              Params.OWNER,
              ownerFqn));
      upsertAnnotationsByFqn(fqn, param);
    }
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
                      Params.FQN,
                      fqn,
                      Params.NAME,
                      v.getNameAsString(),
                      "type",
                      v.getTypeAsString(),
                      Params.IS_STATIC,
                      field.isStatic(),
                      Params.VISIBILITY,
                      field.getAccessSpecifier().asString(),
                      Params.OWNER,
                      ownerFqn));
              upsertAnnotationsByFqn(fqn, field);
            });
  }

  private void upsertMethod(String ownerFqn, MethodDeclaration method) {
    String signature = buildSignature(ownerFqn, method);
    runWithRetry(
        Cypher.CYPHER_UPSERT_METHOD,
        Map.of(
            Params.SIG,
            signature,
            Params.NAME,
            method.getNameAsString(),
            Params.RET,
            method.getTypeAsString(),
            Params.IS_STATIC,
            method.isStatic(),
            Params.VISIBILITY,
            method.getAccessSpecifier().asString(),
            Params.START,
            method.getBegin().map(p -> p.line).orElse(0),
            Params.END,
            method.getEnd().map(p -> p.line).orElse(0),
            Params.OWNER,
            ownerFqn));
    upsertAnnotationsBySig(signature, method);
  }

  private void upsertConstructor(String ownerFqn, ConstructorDeclaration ctor) {
    String signature = buildConstructorSignature(ownerFqn, ctor);
    runWithRetry(
        Cypher.CYPHER_UPSERT_METHOD,
        Map.of(
            Params.SIG,
            signature,
            Params.NAME,
            Labels.INIT,
            Params.RET,
            Labels.VOID,
            Params.IS_STATIC,
            false,
            Params.VISIBILITY,
            ctor.getAccessSpecifier().asString(),
            Params.START,
            ctor.getBegin().map(p -> p.line).orElse(0),
            Params.END,
            ctor.getEnd().map(p -> p.line).orElse(0),
            Params.OWNER,
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
                          Map.of(Params.CALLER, callerSig, Params.CALLEE, calleeSig));
                    }));
  }

  /**
   * Resolves each annotation on {@code node} and writes an {@code ANNOTATED_WITH} edge from the
   * element identified by {@code ownerFqn}. Falls back to the simple annotation name when the
   * symbol resolver cannot determine the FQN.
   */
  private void upsertAnnotationsByFqn(String ownerFqn, NodeWithAnnotations<?> node) {
    upsertAnnotations(Params.OWNER, ownerFqn, node, Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_FQN);
  }

  /**
   * Resolves each annotation on {@code node} and writes an {@code ANNOTATED_WITH} edge from the
   * method identified by {@code sig}. Falls back to the simple annotation name when the symbol
   * resolver cannot determine the FQN.
   */
  private void upsertAnnotationsBySig(String sig, NodeWithAnnotations<?> node) {
    upsertAnnotations(Params.SIG, sig, node, Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_SIG);
  }

  private void upsertAnnotations(
      String paramKey, String paramValue, NodeWithAnnotations<?> node, String cypher) {
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
              runWithRetry(cypher, Map.of(paramKey, paramValue, Params.ANNOT_FQN, annotFqn));
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
    allParams.put(Labels.PROJECT, project);
    long backoffMs = INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
      try {
        session.run(cypher, allParams).consume();
        return;
      } catch (RuntimeException e) {
        backoffMs = proceedException(cypher, e, attempt, backoffMs);
      }
    }
  }

  /**
   * Runs a Cypher query that returns a single numeric result column, with retry-on-conflict.
   * Auto-injects the {@code project} parameter.
   */
  private long runCountWithRetry(
      String cypher, String extraKey, Object extraValue, String resultKey) {
    Map<String, Object> allParams =
        new HashMap<>(Map.of(extraKey, extraValue, Labels.PROJECT, project));
    long backoffMs = INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
      try {
        return session.run(cypher, allParams).single().get(resultKey).asLong();
      } catch (RuntimeException e) {
        backoffMs = proceedException(cypher, e, attempt, backoffMs);
      }
    }
    return 0L;
  }

  /**
   * Proceeds with exception handling for retryable Cypher operations.
   *
   * @param cypher The Cypher query that failed.
   * @param e The caught runtime exception.
   * @param attempt The current retry attempt number.
   * @param backoffMs The current backoff time in milliseconds.
   * @return The updated backoff time in milliseconds.
   */
  private long proceedException(String cypher, RuntimeException e, int attempt, long backoffMs) {
    if (!isRetryable(e)) {
      throw e;
    }
    if (attempt == MAX_RETRY_ATTEMPTS) {
      throw new ProcessingException(
          "Cypher failed after " + MAX_RETRY_ATTEMPTS + " attempts: " + cypher, e);
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
    return backoffMs;
  }
}
