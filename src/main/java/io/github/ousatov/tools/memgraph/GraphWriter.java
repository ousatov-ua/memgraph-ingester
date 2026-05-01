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
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes all Cypher upsert operations for a single Bolt session.
 *
 * <p>Each instance wraps exactly one {@link Session}. In sequential mode the orchestrator creates
 * one instance; in parallel mode each worker thread creates its own, ensuring no session is shared
 * across threads.
 *
 * <p>In sequential mode, callers may open an explicit per-file transaction via {@link
 * #beginFileTransaction()} / {@link #commitFileTransaction()} / {@link #rollbackFileTransaction()}
 * to batch all writes for a file into a single Bolt round-trip, which eliminates 50–100 autocommit
 * transactions per file. This is safe only when there is exactly one writer (sequential mode),
 * because Memgraph MVCC will abort concurrent transactions that MERGE the same shared nodes (e.g.,
 * {@code :Package}, parent {@code :Class}/{@code :Interface}, {@code :Annotation}).
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
   * Non-null while an explicit file-level transaction is open (sequential mode only). When set,
   * {@link #runWithRetry} routes through this transaction instead of autocommit session.run().
   */
  private Transaction currentTx;

  /**
   * @param session Bolt session — must not be shared with other threads
   * @param project project name used to scope all Cypher operations
   */
  public GraphWriter(Session session, String project) {
    this.session = session;
    this.project = project;
  }

  /**
   * Opens an explicit Bolt transaction so that all subsequent {@link #runWithRetry} calls are
   * batched into a single round-trip. Must only be called in sequential (single-writer) mode;
   * concurrent writers sharing the same nodes will trigger Memgraph MVCC conflicts.
   *
   * <p>Call {@link #commitFileTransaction()} when all writes succeed, or {@link
   * #rollbackFileTransaction()} on any failure.
   */
  public void beginFileTransaction() {
    currentTx = session.beginTransaction();
  }

  /**
   * Commits the current file-level transaction and clears it. Must be paired with a prior call to
   * {@link #beginFileTransaction()}.
   */
  public void commitFileTransaction() {
    if (currentTx != null) {
      currentTx.commit();
      currentTx = null;
    }
  }

  /**
   * Rolls back the current file-level transaction and clears it. Called on any write failure to
   * discard partial state so the file can be retried cleanly.
   */
  public void rollbackFileTransaction() {
    if (currentTx != null) {
      try {
        currentTx.rollback();
      } catch (Exception e) {
        log.debug("Rollback failed: {}", e.getMessage());
      } finally {
        currentTx = null;
      }
    }
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
    } catch (Exception _) {
      return Optional.empty();
    }
  }

  /**
   * Builds the method signature using symbol resolution when available, falling back to
   * per-parameter resolution. Both paths produce the same format as {@code
   * call.resolve().getQualifiedSignature()}, so CALLS edges connect correctly.
   */
  private static String buildSignature(String ownerFqn, MethodDeclaration m) {
    try {
      return m.resolve().getQualifiedSignature();
    } catch (Exception _) {
      String params =
          m.getParameters().stream()
              .map(GraphWriter::resolveParamType)
              .collect(Collectors.joining(", "));
      return ownerFqn + "." + m.getNameAsString() + "(" + params + ")";
    }
  }

  /**
   * Builds the constructor signature using symbol resolution for FQN parameter types, falling back
   * to per-parameter resolution. Uses the {@code <init>} convention for consistent identification.
   */
  private static String buildConstructorSignature(String ownerFqn, ConstructorDeclaration ctor) {
    try {
      String resolved = ctor.resolve().getQualifiedSignature();
      int parenIdx = resolved.indexOf('(');
      String params = resolved.substring(parenIdx + 1, resolved.length() - 1);
      return ownerFqn + "." + Labels.INIT + "(" + params + ")";
    } catch (Exception _) {
      String params =
          ctor.getParameters().stream()
              .map(GraphWriter::resolveParamType)
              .collect(Collectors.joining(", "));
      return ownerFqn + "." + Labels.INIT + "(" + params + ")";
    }
  }

  /**
   * Resolves a single parameter type to its FQN, falling back to the source-level name when
   * resolution fails. This ensures each parameter is independently resolved rather than treating
   * the whole method signature as all-or-nothing.
   */
  private static String resolveParamType(Parameter p) {
    try {
      return p.getType().resolve().describe();
    } catch (Exception _) {
      return p.getType().asString();
    }
  }

  /** Constructs a fully qualified name from {@code pkg} and {@code simpleName}. */
  private static String buildFqn(String pkg, String simpleName) {
    return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
  }

  /** Extracts the simple name from a fully qualified name. */
  static String nameFromFqn(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  /** Extracts the package name from a fully qualified name. */
  static String packageFromFqn(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? "" : fqn.substring(0, dot);
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
    runWithRetry(Cypher.CYPHER_WIPE_PROJECT_MEMORIES, Collections.emptyMap());
  }

  /** Refreshes {@code :CodeRef} resolution edges to the current project-scoped code graph. */
  public void resolveCodeRefs() {
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_CODE, Collections.emptyMap());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_PACKAGE, Collections.emptyMap());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_FILE, Collections.emptyMap());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_CLASS, Collections.emptyMap());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_INTERFACE, Collections.emptyMap());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_ANNOTATION, Collections.emptyMap());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_METHOD, Collections.emptyMap());
    runWithRetry(Cypher.CYPHER_RESOLVE_CODE_REFS_FIELD, Collections.emptyMap());
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
      var value = result.single().get(Params.LAST_MODIFIED);
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
      Map<String, Long> mtimes = HashMap.newHashMap(files.size() * 2);
      while (result.hasNext()) {
        var currentRec = result.next();
        String path = currentRec.get("path").asString(null);
        var value = currentRec.get(Params.LAST_MODIFIED);
        if (path != null && !value.isNull()) {
          mtimes.put(path, value.asLong());
        }
      }
      return mtimes;
    } catch (RuntimeException e) {
      log.warn(
          "Could not batch-fetch lastModified values, incremental mode will re-ingest all files:"
              + " {}",
          e.getMessage());
      return Collections.emptyMap();
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
        Map.of(Params.PATH, file.toString(), Params.LAST_MODIFIED, lastModified));
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
            false,
            Params.IS_FINAL,
            true));
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
            true,
            Params.IS_FINAL,
            true));
    upsertAnnotationsByFqn(fqn, decl);
    upsertImplementedTypes(fqn, decl);
    decl.getFields().forEach(f -> upsertField(fqn, f));
    upsertRecordComponents(fqn, decl);
    decl.getMethods().forEach(m -> upsertMethod(fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(fqn, c));
    upsertRecordCanonicalConstructor(fqn, decl);
    upsertRecordAccessors(fqn, decl);
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
    decl.getMethods().forEach(m -> upsertCallEdges(buildSignature(fqn, m), fqn, m));
    decl.getConstructors().forEach(c -> upsertCallEdges(buildConstructorSignature(fqn, c), fqn, c));
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
    decl.getMethods().forEach(m -> upsertCallEdges(buildSignature(fqn, m), fqn, m));
    decl.getConstructors().forEach(c -> upsertCallEdges(buildConstructorSignature(fqn, c), fqn, c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeCallEdgesInternal(pkg, fqn, nested));
  }

  private void upsertTypeCallEdgesInternal(
      String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn =
        outerFqn != null ? outerFqn + "$" + decl.getNameAsString() : genDeclName(pkg, decl);
    decl.getMethods().forEach(m -> upsertCallEdges(buildSignature(fqn, m), fqn, m));
    decl.getConstructors().forEach(c -> upsertCallEdges(buildConstructorSignature(fqn, c), fqn, c));
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
              decl.getAccessSpecifier().asString(),
              Params.IS_FINAL,
              false));
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
              false,
              Params.IS_FINAL,
              decl.isFinal()));
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
        decl.isInterface()
            ? Cypher.CYPHER_UPSERT_INTERFACE_EXTENDS
            : Cypher.CYPHER_UPSERT_EXTENDS_CLASS;
    decl.getExtendedTypes()
        .forEach(
            ext ->
                withResolvedType(
                    ext,
                    parent ->
                        runWithRetry(
                            extendsCypher,
                            Map.of(
                                Params.CHILD,
                                fqn,
                                Params.PARENT,
                                parent,
                                Params.PARENT_NAME,
                                nameFromFqn(parent),
                                Params.PARENT_PKG,
                                packageFromFqn(parent)))));
    decl.getImplementedTypes()
        .forEach(
            impl ->
                withResolvedType(
                    impl,
                    iface ->
                        runWithRetry(
                            Cypher.CYPHER_UPSERT_IMPLEMENTS,
                            Map.of(
                                Params.CHILD,
                                fqn,
                                Params.IFACE,
                                iface,
                                Params.IFACE_NAME,
                                nameFromFqn(iface),
                                Params.IFACE_PKG,
                                packageFromFqn(iface)))));
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
                            Map.of(
                                Params.CHILD,
                                fqn,
                                Params.IFACE,
                                iface,
                                Params.IFACE_NAME,
                                nameFromFqn(iface),
                                Params.IFACE_PKG,
                                packageFromFqn(iface)))));
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
    upsertMethodNode(
        ownerFqn,
        signature,
        method.getNameAsString(),
        method.getTypeAsString(),
        method.isStatic(),
        method.getAccessSpecifier().asString(),
        method.getBegin().map(p -> p.line).orElse(0),
        method.getEnd().map(p -> p.line).orElse(0),
        false);
    upsertAnnotationsBySig(signature, method);
  }

  private void upsertConstructor(String ownerFqn, ConstructorDeclaration ctor) {
    String signature = buildConstructorSignature(ownerFqn, ctor);
    upsertMethodNode(
        ownerFqn,
        signature,
        Labels.INIT,
        Labels.VOID,
        false,
        ctor.getAccessSpecifier().asString(),
        ctor.getBegin().map(p -> p.line).orElse(0),
        ctor.getEnd().map(p -> p.line).orElse(0),
        false);
    upsertAnnotationsBySig(signature, ctor);
  }

  /** Shared helper that creates or updates a {@code :Method} node with all properties. */
  private void upsertMethodNode(
      String ownerFqn,
      String signature,
      String name,
      String returnType,
      boolean isStatic,
      String visibility,
      int startLine,
      int endLine,
      boolean isSynthetic) {
    runWithRetry(
        Cypher.CYPHER_UPSERT_METHOD,
        Map.of(
            Params.SIG,
            signature,
            Params.NAME,
            name,
            Params.RET,
            returnType,
            Params.IS_STATIC,
            isStatic,
            Params.VISIBILITY,
            visibility,
            Params.START,
            startLine,
            Params.END,
            endLine,
            Params.OWNER,
            ownerFqn,
            Params.IS_SYNTHETIC,
            isSynthetic));
  }

  /**
   * Synthesizes the canonical constructor for a record if no explicit canonical constructor is
   * declared. The canonical constructor has the same parameter list as the record components.
   */
  private void upsertRecordCanonicalConstructor(String fqn, RecordDeclaration decl) {
    String canonicalParams =
        decl.getParameters().stream()
            .map(GraphWriter::resolveParamType)
            .collect(Collectors.joining(", "));
    String canonicalSig = fqn + "." + Labels.INIT + "(" + canonicalParams + ")";
    boolean hasCanonical =
        decl.getConstructors().stream()
            .anyMatch(c -> buildConstructorSignature(fqn, c).equals(canonicalSig));
    if (!hasCanonical) {
      upsertMethodNode(
          fqn,
          canonicalSig,
          Labels.INIT,
          Labels.VOID,
          false,
          decl.getAccessSpecifier().asString(),
          0,
          0,
          true);
    }
  }

  /**
   * Synthesizes accessor methods for record components that don't have an explicit accessor
   * declared.
   */
  private void upsertRecordAccessors(String fqn, RecordDeclaration decl) {
    var explicitMethods =
        decl.getMethods().stream()
            .filter(m -> m.getParameters().isEmpty())
            .map(MethodDeclaration::getNameAsString)
            .collect(Collectors.toSet());

    for (Parameter param : decl.getParameters()) {
      String accessorName = param.getNameAsString();
      if (!explicitMethods.contains(accessorName)) {
        String sig = fqn + "." + accessorName + "()";
        upsertMethodNode(
            fqn, sig, accessorName, param.getTypeAsString(), false, "public", 0, 0, true);
      }
    }
  }

  /**
   * Finds all call-like expressions inside {@code bodyNode}, resolves each callee, and writes a
   * {@code CALLS} edge. Handles regular method calls, method references, constructor invocations
   * ({@code new X(...)}), constructor delegation ({@code this(...)} / {@code super(...)}), and
   * constructor references ({@code Type::new}).
   *
   * <p>For unresolved calls, falls back to name-based matching within the owning (or inferred) type
   * — only when exactly one method has that name.
   */
  private void upsertCallEdges(String callerSig, String ownerFqn, Node bodyNode) {
    bodyNode
        .findAll(MethodCallExpr.class)
        .forEach(
            call -> {
              try {
                ResolvedMethodDeclaration resolved = call.resolve();
                String calleeSig = resolved.getQualifiedSignature();
                runWithRetry(
                    Cypher.CYPHER_UPSERT_CALL,
                    Map.of(Params.CALLER, callerSig, Params.CALLEE, calleeSig));
              } catch (Exception _) {
                if (call.getScope().isEmpty()) {
                  runWithRetry(
                      Cypher.CYPHER_UPSERT_CALL_BY_NAME,
                      Map.of(
                          Params.CALLER,
                          callerSig,
                          Params.OWNER_FQN,
                          ownerFqn,
                          Params.CALLEE_NAME,
                          call.getNameAsString()));
                } else {
                  resolveScopeTypeFqn(call)
                      .ifPresent(
                          scopeFqn ->
                              runWithRetry(
                                  Cypher.CYPHER_UPSERT_CALL_BY_NAME,
                                  Map.of(
                                      Params.CALLER,
                                      callerSig,
                                      Params.OWNER_FQN,
                                      scopeFqn,
                                      Params.CALLEE_NAME,
                                      call.getNameAsString())));
                }
              }
            });

    bodyNode
        .findAll(MethodReferenceExpr.class)
        .forEach(ref -> upsertMethodReferenceEdge(callerSig, ownerFqn, ref));

    bodyNode
        .findAll(ObjectCreationExpr.class)
        .forEach(creation -> upsertObjectCreationEdge(callerSig, creation));

    bodyNode
        .findAll(ExplicitConstructorInvocationStmt.class)
        .forEach(stmt -> upsertExplicitCtorEdge(callerSig, ownerFqn, stmt));
  }

  /**
   * Resolves a method reference ({@code Type::method}) and writes a {@code CALLS} edge. Falls back
   * to name-based matching when the scope type matches the owning class. Constructor references
   * ({@code Type::new}) are dispatched to {@link #upsertConstructorReferenceEdge}.
   */
  private void upsertMethodReferenceEdge(
      String callerSig, String ownerFqn, MethodReferenceExpr ref) {
    String identifier = ref.getIdentifier();
    if ("new".equals(identifier)) {
      upsertConstructorReferenceEdge(callerSig, ref);
      return;
    }
    try {
      ResolvedMethodDeclaration resolved = ref.resolve();
      String calleeSig = resolved.getQualifiedSignature();
      runWithRetry(
          Cypher.CYPHER_UPSERT_CALL, Map.of(Params.CALLER, callerSig, Params.CALLEE, calleeSig));
    } catch (Exception _) {
      var scope = ref.getScope();
      if (scope.isTypeExpr()
          && scope.asTypeExpr().getType().isClassOrInterfaceType()
          && scope
              .asTypeExpr()
              .getType()
              .asClassOrInterfaceType()
              .getNameAsString()
              .equals(nameFromFqn(ownerFqn))) {
        runWithRetry(
            Cypher.CYPHER_UPSERT_CALL_BY_NAME,
            Map.of(
                Params.CALLER,
                callerSig,
                Params.OWNER_FQN,
                ownerFqn,
                Params.CALLEE_NAME,
                identifier));
      }
    }
  }

  /**
   * Writes a {@code CALLS} edge for a constructor invocation ({@code new X(...)}). Tries full
   * resolution first; falls back to type-inference + name-based matching with {@code <init>}.
   */
  private void upsertObjectCreationEdge(String callerSig, ObjectCreationExpr creation) {
    try {
      var resolvedCtor = creation.resolve();
      String typeFqn = resolvedCtor.declaringType().getQualifiedName();
      String qualSig = resolvedCtor.getQualifiedSignature();
      int parenIdx = qualSig.indexOf('(');
      String params = qualSig.substring(parenIdx + 1, qualSig.length() - 1);
      String calleeSig = typeFqn + "." + Labels.INIT + "(" + params + ")";
      runWithRetry(
          Cypher.CYPHER_UPSERT_CALL, Map.of(Params.CALLER, callerSig, Params.CALLEE, calleeSig));
    } catch (Exception _) {
      resolveOrInferFqn(creation.getType())
          .ifPresent(
              fqn ->
                  runWithRetry(
                      Cypher.CYPHER_UPSERT_CALL_BY_NAME,
                      Map.of(
                          Params.CALLER,
                          callerSig,
                          Params.OWNER_FQN,
                          fqn,
                          Params.CALLEE_NAME,
                          Labels.INIT)));
    }
  }

  /**
   * Writes a {@code CALLS} edge for an explicit constructor delegation ({@code this(...)} or {@code
   * super(...)}). Tries full resolution first; for unresolved {@code this(...)}, falls back to
   * name-based matching within {@code ownerFqn}.
   */
  private void upsertExplicitCtorEdge(
      String callerSig, String ownerFqn, ExplicitConstructorInvocationStmt stmt) {
    try {
      var resolvedCtor = stmt.resolve();
      String typeFqn = resolvedCtor.declaringType().getQualifiedName();
      String qualSig = resolvedCtor.getQualifiedSignature();
      int parenIdx = qualSig.indexOf('(');
      String params = qualSig.substring(parenIdx + 1, qualSig.length() - 1);
      String calleeSig = typeFqn + "." + Labels.INIT + "(" + params + ")";
      runWithRetry(
          Cypher.CYPHER_UPSERT_CALL, Map.of(Params.CALLER, callerSig, Params.CALLEE, calleeSig));
    } catch (Exception _) {
      if (stmt.isThis()) {
        runWithRetry(
            Cypher.CYPHER_UPSERT_CALL_BY_NAME,
            Map.of(
                Params.CALLER,
                callerSig,
                Params.OWNER_FQN,
                ownerFqn,
                Params.CALLEE_NAME,
                Labels.INIT));
      }
    }
  }

  /**
   * Writes a {@code CALLS} edge for a constructor reference ({@code Type::new}). Resolves the scope
   * type and uses name-based matching with {@code <init>}.
   */
  private void upsertConstructorReferenceEdge(String callerSig, MethodReferenceExpr ref) {
    var scope = ref.getScope();
    if (scope.isTypeExpr() && scope.asTypeExpr().getType().isClassOrInterfaceType()) {
      ClassOrInterfaceType type = scope.asTypeExpr().getType().asClassOrInterfaceType();
      resolveOrInferFqn(type)
          .ifPresent(
              fqn ->
                  runWithRetry(
                      Cypher.CYPHER_UPSERT_CALL_BY_NAME,
                      Map.of(
                          Params.CALLER,
                          callerSig,
                          Params.OWNER_FQN,
                          fqn,
                          Params.CALLEE_NAME,
                          Labels.INIT)));
    }
  }

  /**
   * Resolves a type to its FQN via the symbol solver, falling back to import-based inference.
   * Returns empty only when both strategies fail.
   */
  private static Optional<String> resolveOrInferFqn(ClassOrInterfaceType type) {
    Optional<String> resolved = resolveQualifiedName(type);
    if (resolved.isPresent()) {
      return resolved;
    }
    String inferred = inferFqnFromImports(type);
    return inferred.equals(type.getNameAsString()) ? Optional.empty() : Optional.of(inferred);
  }

  /**
   * Attempts to determine the FQN of the type targeted by a scoped method call. Tries expression
   * type resolution first (works for instance calls), then import-based inference (works for static
   * calls via imported type names).
   */
  private static Optional<String> resolveScopeTypeFqn(MethodCallExpr call) {
    var scope = call.getScope().orElse(null);
    if (scope == null) {
      return Optional.empty();
    }
    try {
      return scope
          .calculateResolvedType()
          .asReferenceType()
          .getTypeDeclaration()
          .map(ResolvedTypeDeclaration::getQualifiedName);
    } catch (Exception _) {
      // Expression-level resolution failed; try import inference for static calls.
    }
    if (scope.isNameExpr()) {
      String name = scope.asNameExpr().getNameAsString();
      return call.findCompilationUnit()
          .flatMap(
              cu -> {
                for (var imp : cu.getImports()) {
                  if (!imp.isAsterisk()
                      && !imp.isStatic()
                      && imp.getName().getIdentifier().equals(name)) {
                    return Optional.of(imp.getNameAsString());
                  }
                }
                return Optional.<String>empty();
              });
    }
    return Optional.empty();
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
              } catch (Exception _) {
                annotFqn = ann.getNameAsString();
              }
              runWithRetry(
                  cypher,
                  Map.of(
                      paramKey,
                      paramValue,
                      Params.ANNOT_FQN,
                      annotFqn,
                      Params.ANNOT_NAME,
                      nameFromFqn(annotFqn)));
            });
  }

  /**
   * Resolves {@code type} and invokes {@code action} with the FQN. Falls back to import-based
   * inference when the symbol solver cannot determine the FQN, then to the source-level name.
   */
  private void withResolvedType(ClassOrInterfaceType type, Consumer<String> action) {
    Optional<String> resolved = resolveQualifiedName(type);
    action.accept(resolved.orElseGet(() -> inferFqnFromImports(type)));
  }

  /**
   * Infers a type FQN from the compilation unit's imports when symbol resolution fails. Checks
   * explicit single-type imports first, then handles scoped names (e.g., {@code
   * ExtensionContext.Store.CloseableResource}) by matching the top-level scope against imports.
   * Falls back to the source-level type name if no import matches.
   */
  private static String inferFqnFromImports(ClassOrInterfaceType type) {
    String simpleName = type.getNameAsString();
    String fullName = type.asString();
    return type.findCompilationUnit()
        .flatMap(
            cu -> {
              for (var imp : cu.getImports()) {
                if (!imp.isAsterisk()
                    && !imp.isStatic()
                    && imp.getName().getIdentifier().equals(simpleName)) {
                  return Optional.of(imp.getNameAsString());
                }
              }
              if (fullName.contains(".")) {
                String topLevel = fullName.substring(0, fullName.indexOf('.'));
                for (var imp : cu.getImports()) {
                  if (!imp.isAsterisk()
                      && !imp.isStatic()
                      && imp.getName().getIdentifier().equals(topLevel)) {
                    return Optional.of(
                        imp.getNameAsString() + fullName.substring(fullName.indexOf('.')));
                  }
                }
              }
              return Optional.<String>empty();
            })
        .orElse(fullName);
  }

  /**
   * Runs {@code cypher} with retry-on-conflict. Routes through the open file-level transaction when
   * one is active (sequential batch mode); otherwise falls back to autocommit session.run().
   * Auto-injects the {@code project} parameter so callers do not need to include it in {@code
   * params}.
   */
  private void runWithRetry(String cypher, Map<String, Object> params) {
    Map<String, Object> allParams = new HashMap<>(params);
    allParams.put(Labels.PROJECT, project);
    long backoffMs = INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
      try {
        if (currentTx != null) {
          currentTx.run(cypher, allParams).consume();
        } else {
          session.run(cypher, allParams).consume();
        }
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
