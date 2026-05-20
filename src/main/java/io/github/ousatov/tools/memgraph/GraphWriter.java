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
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

  private static final int WIPE_BATCH_SIZE = 10_000;

  private final CypherExecutor cypher;

  /**
   * @param session Bolt session — must not be shared with other threads
   * @param project project name used to scope all Cypher operations
   */
  public GraphWriter(Session session, String project) {
    this.cypher = new CypherExecutor(session, project);
  }

  /**
   * Opens an explicit Bolt transaction so that all subsequent Cypher writes are batched into a
   * single round-trip. Must only be called in sequential (single-writer) mode; concurrent writers
   * sharing the same nodes will trigger Memgraph MVCC conflicts.
   *
   * <p>Call {@link #commitFileTransaction()} when all writes succeed, or {@link
   * #rollbackFileTransaction()} on any failure.
   */
  public void beginFileTransaction() {
    cypher.beginTransaction();
  }

  /**
   * Commits the current file-level transaction and clears it. Must be paired with a prior call to
   * {@link #beginFileTransaction()}.
   */
  public void commitFileTransaction() {
    cypher.commitTransaction();
  }

  /**
   * Rolls back the current file-level transaction and clears it. Called on any write failure to
   * discard partial state so the file can be retried cleanly.
   */
  public void rollbackFileTransaction() {
    cypher.rollbackTransaction();
  }

  static boolean isRetryable(RuntimeException e) {
    return CypherExecutor.isRetryable(e);
  }

  /** Deletes the project-scoped {@code :Code} graph in batches, keeping the {@code :Project}. */
  public void wipe() {
    long deleted;
    do {
      deleted =
          cypher.runCount(
              Cypher.CYPHER_WIPE_PROJECT_CODE_BATCH, "batchSize", WIPE_BATCH_SIZE, "deleted");
    } while (deleted > 0);
  }

  /** Deletes the project-scoped {@code :Memory} graph while keeping the {@code :Project} anchor. */
  public void wipeMemories() {
    cypher.run(Cypher.CYPHER_WIPE_PROJECT_MEMORIES, Map.of());
  }

  /**
   * Removes placeholder {@code :Method} nodes that were created as callee stubs but never fully
   * ingested (external/JDK methods have no {@code startLine}).
   */
  public void deletePhantomMethods() {
    cypher.run(Cypher.CYPHER_DELETE_PHANTOM_METHODS, Map.of());
  }

  /** Refreshes {@code :CodeRef} resolution edges to the current project-scoped code graph. */
  public void resolveCodeRefs() {
    List.of(
            Cypher.CYPHER_RESOLVE_CODE_REFS_CODE,
            Cypher.CYPHER_RESOLVE_CODE_REFS_PACKAGE,
            Cypher.CYPHER_RESOLVE_CODE_REFS_FILE,
            Cypher.CYPHER_RESOLVE_CODE_REFS_CLASS,
            Cypher.CYPHER_RESOLVE_CODE_REFS_INTERFACE,
            Cypher.CYPHER_RESOLVE_CODE_REFS_ANNOTATION,
            Cypher.CYPHER_RESOLVE_CODE_REFS_METHOD,
            Cypher.CYPHER_RESOLVE_CODE_REFS_FIELD)
        .forEach(q -> cypher.run(q, Map.of()));
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
    try {
      return cypher.read(
          Cypher.CYPHER_GET_FILES_LAST_MODIFIED,
          Map.of("paths", paths),
          result -> {
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
          });
    } catch (RuntimeException e) {
      log.warn(
          "Could not batch-fetch lastModified values, incremental mode will re-ingest all files:"
              + " {}",
          e.getMessage());
      return Map.of();
    }
  }

  /** Creates or refreshes the {@code :Project -> :Code} and {@code :Project -> :Memory} anchors. */
  public void upsertProject(Path sourceRoot) {
    cypher.run(Cypher.CYPHER_UPSERT_PROJECT, Map.of("sourceRoot", sourceRoot.toString()));
  }

  /** Backfills method owner metadata for graphs ingested before owner properties existed. */
  public void backfillMethodOwnerMetadata() {
    cypher.run(Cypher.CYPHER_BACKFILL_METHOD_OWNER_METADATA, Map.of());
  }

  /** Upserts a {@code :File} node and links it to the code anchor. */
  public void upsertFile(Path file) {
    long lastModified;
    try {
      lastModified = Files.getLastModifiedTime(file).toMillis();
    } catch (IOException _) {
      lastModified = -1L;
    }
    cypher.run(
        Cypher.CYPHER_UPSERT_FILE,
        Map.of(Params.PATH, file.toString(), Params.LAST_MODIFIED, lastModified));
  }

  /** Upserts a {@code :Package} node and links it to the code anchor. */
  public void upsertPackage(String pkg) {
    cypher.run(Cypher.CYPHER_UPSERT_PACKAGE, Map.of(Params.NAME, pkg));
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
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    cypher.run(
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
    nestedClassDeclarationsOf(decl.getMembers())
        .forEach(nested -> upsertTypeInternal(file, pkg, fqn, nested));
  }

  /**
   * Upserts a record declaration as a {@code :Class} with {@code isRecord = true}, including its
   * fields, methods, constructors, implemented interfaces, and nested types.
   */
  public void upsertRecord(Path file, String pkg, RecordDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    cypher.run(
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
    nestedClassDeclarationsOf(decl.getMembers())
        .forEach(nested -> upsertTypeInternal(file, pkg, fqn, nested));
  }

  /**
   * Upserts an {@code @interface} declaration as an {@code :Annotation} node, including {@code
   * ANNOTATED_WITH} edges for any meta-annotations applied to it.
   */
  public void upsertAnnotation(Path file, String pkg, AnnotationDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    cypher.run(
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
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    upsertCallEdgesForDecl(pkg, fqn, decl.getMethods(), decl.getConstructors(), decl.getMembers());
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertRecordCallEdges(String pkg, RecordDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    upsertCallEdgesForDecl(pkg, fqn, decl.getMethods(), decl.getConstructors(), decl.getMembers());
  }

  private void upsertTypeCallEdgesInternal(
      String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn =
        outerFqn != null
            ? outerFqn + "$" + decl.getNameAsString()
            : JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    decl.getMethods().forEach(m -> upsertCallEdges(JavaTypeNames.buildSignature(fqn, m), fqn, m));
    decl.getConstructors()
        .forEach(c -> upsertCallEdges(JavaTypeNames.buildConstructorSignature(fqn, c), fqn, c));
    nestedClassDeclarationsOf(decl.getMembers())
        .forEach(nested -> upsertTypeCallEdgesInternal(pkg, fqn, nested));
  }

  private void upsertTypeInternal(
      Path file, String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn =
        outerFqn != null
            ? outerFqn + "$" + decl.getNameAsString()
            : JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    if (decl.isInterface()) {
      cypher.run(
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
      cypher.run(
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
    if (!decl.isInterface() && decl.getConstructors().isEmpty()) {
      upsertImplicitDefaultConstructor(fqn, decl);
    }
    // Recurse into directly nested class/interface declarations with correct FQN.
    nestedClassDeclarationsOf(decl.getMembers())
        .forEach(nested -> upsertTypeInternal(file, pkg, fqn, nested));
  }

  /**
   * Returns a stream of directly nested {@link ClassOrInterfaceDeclaration} nodes from {@code
   * members}. Only handles class and interface declarations; nested enums, records, and annotation
   * types are excluded (pre-existing limitation).
   */
  private static Stream<ClassOrInterfaceDeclaration> nestedClassDeclarationsOf(List<?> members) {
    return members.stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(ClassOrInterfaceDeclaration.class::cast);
  }

  /**
   * Upserts {@code CALLS} edges for methods, constructors, and directly nested class declarations
   * of a type body. Used by {@link #upsertEnumCallEdges} and {@link #upsertRecordCallEdges}.
   */
  private void upsertCallEdgesForDecl(
      String pkg,
      String fqn,
      List<MethodDeclaration> methods,
      List<ConstructorDeclaration> constructors,
      List<?> members) {
    methods.forEach(m -> upsertCallEdges(JavaTypeNames.buildSignature(fqn, m), fqn, m));
    constructors.forEach(
        c -> upsertCallEdges(JavaTypeNames.buildConstructorSignature(fqn, c), fqn, c));
    nestedClassDeclarationsOf(members)
        .forEach(nested -> upsertTypeCallEdgesInternal(pkg, fqn, nested));
  }

  private void upsertInheritance(String fqn, ClassOrInterfaceDeclaration decl) {
    String extendsCypher =
        decl.isInterface()
            ? Cypher.CYPHER_UPSERT_INTERFACE_EXTENDS
            : Cypher.CYPHER_UPSERT_EXTENDS_CLASS;
    decl.getExtendedTypes()
        .forEach(
            ext ->
                JavaTypeNames.withResolvedType(
                    ext,
                    parent ->
                        cypher.run(
                            extendsCypher,
                            Map.of(
                                Params.CHILD,
                                fqn,
                                Params.PARENT,
                                parent,
                                Params.PARENT_NAME,
                                JavaTypeNames.nameFromFqn(parent),
                                Params.PARENT_PKG,
                                JavaTypeNames.packageFromFqn(parent)))));
    decl.getImplementedTypes()
        .forEach(
            impl ->
                JavaTypeNames.withResolvedType(
                    impl,
                    iface ->
                        cypher.run(
                            Cypher.CYPHER_UPSERT_IMPLEMENTS,
                            Map.of(
                                Params.CHILD,
                                fqn,
                                Params.IFACE,
                                iface,
                                Params.IFACE_NAME,
                                JavaTypeNames.nameFromFqn(iface),
                                Params.IFACE_PKG,
                                JavaTypeNames.packageFromFqn(iface)))));
  }

  /** Writes {@code IMPLEMENTS} edges for enums and records that implement interfaces. */
  private void upsertImplementedTypes(String fqn, NodeWithImplements<?> decl) {
    decl.getImplementedTypes()
        .forEach(
            impl ->
                JavaTypeNames.withResolvedType(
                    impl,
                    iface ->
                        cypher.run(
                            Cypher.CYPHER_UPSERT_IMPLEMENTS,
                            Map.of(
                                Params.CHILD,
                                fqn,
                                Params.IFACE,
                                iface,
                                Params.IFACE_NAME,
                                JavaTypeNames.nameFromFqn(iface),
                                Params.IFACE_PKG,
                                JavaTypeNames.packageFromFqn(iface)))));
  }

  /** Upserts record components (parameters) as {@code :Field} nodes. */
  private void upsertRecordComponents(String ownerFqn, RecordDeclaration decl) {
    for (Parameter param : decl.getParameters()) {
      String fqn = ownerFqn + "#" + param.getNameAsString();
      cypher.run(
          Cypher.CYPHER_UPSERT_FIELD,
          Map.of(
              Params.FQN,
              fqn,
              Params.NAME,
              param.getNameAsString(),
              Params.TYPE,
              JavaTypeNames.resolveType(param.getType()),
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
              cypher.run(
                  Cypher.CYPHER_UPSERT_FIELD,
                  Map.of(
                      Params.FQN,
                      fqn,
                      Params.NAME,
                      v.getNameAsString(),
                      Params.TYPE,
                      JavaTypeNames.resolveType(v.getType()),
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
    String signature = JavaTypeNames.buildSignature(ownerFqn, method);
    upsertMethodNode(
        new Method(
            ownerFqn,
            signature,
            method.getNameAsString(),
            JavaTypeNames.resolveType(method.getType()),
            method.isStatic(),
            method.getAccessSpecifier().asString(),
            method.getBegin().map(p -> p.line).orElse(0),
            method.getEnd().map(p -> p.line).orElse(0),
            false));
    upsertAnnotationsBySig(signature, method);
  }

  private void upsertConstructor(String ownerFqn, ConstructorDeclaration ctor) {
    String signature = JavaTypeNames.buildConstructorSignature(ownerFqn, ctor);
    upsertMethodNode(
        new Method(
            ownerFqn,
            signature,
            Labels.INIT,
            Labels.VOID,
            false,
            ctor.getAccessSpecifier().asString(),
            ctor.getBegin().map(p -> p.line).orElse(0),
            ctor.getEnd().map(p -> p.line).orElse(0),
            false));
    upsertAnnotationsBySig(signature, ctor);
  }

  /** Shared helper that creates or updates a {@code :Method} node with all properties. */
  private void upsertMethodNode(Method method) {
    cypher.run(
        Cypher.CYPHER_UPSERT_METHOD,
        Map.of(
            Params.SIG,
            method.signature(),
            Params.NAME,
            method.name(),
            Params.RET,
            method.returnType(),
            Params.IS_STATIC,
            method.isStatic(),
            Params.VISIBILITY,
            method.visibility(),
            Params.START,
            method.startLine(),
            Params.END,
            method.endLine(),
            Params.OWNER,
            method.ownerFqn(),
            Params.OWNER_DISPLAY_NAME,
            JavaTypeNames.nameFromFqn(method.ownerFqn()),
            Params.IS_SYNTHETIC,
            method.isSynthetic()));
  }

  /**
   * Synthesizes the canonical constructor for a record if no explicit canonical constructor is
   * declared. The canonical constructor has the same parameter list as the record components.
   */
  private void upsertRecordCanonicalConstructor(String fqn, RecordDeclaration decl) {
    String canonicalParams =
        decl.getParameters().stream()
            .map(JavaTypeNames::resolveParamType)
            .collect(Collectors.joining(", "));
    String canonicalSig = fqn + "." + Labels.INIT + "(" + canonicalParams + ")";
    boolean hasCanonical =
        decl.getConstructors().stream()
            .anyMatch(c -> JavaTypeNames.buildConstructorSignature(fqn, c).equals(canonicalSig));
    if (!hasCanonical) {
      upsertMethodNode(
          new Method(
              fqn,
              canonicalSig,
              Labels.INIT,
              Labels.VOID,
              false,
              decl.getAccessSpecifier().asString(),
              0,
              0,
              true));
    }
  }

  /**
   * Synthesizes the implicit no-arg constructor for a class that declares none. Stored with {@code
   * isSynthetic=true} so it is invisible to default method-count queries but exists as a valid
   * CALLS target — preventing phantom cleanup from erasing {@code new ClassName()} call edges.
   */
  private void upsertImplicitDefaultConstructor(String fqn, ClassOrInterfaceDeclaration decl) {
    upsertMethodNode(
        new Method(
            fqn,
            fqn + "." + Labels.INIT + "()",
            Labels.INIT,
            Labels.VOID,
            false,
            decl.getAccessSpecifier().asString(),
            0,
            0,
            true));
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
            new Method(
                fqn,
                sig,
                accessorName,
                JavaTypeNames.resolveType(param.getType()),
                false,
                "public",
                0,
                0,
                true));
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
                String calleeSig =
                    JavaTypeNames.normalizeNestedFqn(resolved.declaringType().getQualifiedName())
                        + "."
                        + resolved.getName()
                        + resolved
                            .getQualifiedSignature()
                            .substring(resolved.getQualifiedSignature().indexOf('('));
                cypher.run(
                    Cypher.CYPHER_UPSERT_CALL,
                    Map.of(Params.CALLER, callerSig, Params.CALLEE, calleeSig));
              } catch (Exception _) {
                if (call.getScope().isEmpty()) {
                  cypher.run(
                      Cypher.CYPHER_UPSERT_CALL_BY_NAME,
                      Map.of(
                          Params.CALLER,
                          callerSig,
                          Params.OWNER_FQN,
                          ownerFqn,
                          Params.CALLEE_NAME,
                          call.getNameAsString()));
                } else {
                  JavaTypeNames.resolveScopeTypeFqn(call)
                      .ifPresent(
                          scopeFqn ->
                              cypher.run(
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
      String calleeSig =
          JavaTypeNames.normalizeNestedFqn(resolved.declaringType().getQualifiedName())
              + "."
              + resolved.getName()
              + resolved
                  .getQualifiedSignature()
                  .substring(resolved.getQualifiedSignature().indexOf('('));
      cypher.run(
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
              .equals(JavaTypeNames.nameFromFqn(ownerFqn))) {
        cypher.run(
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
      String typeFqn =
          JavaTypeNames.normalizeNestedFqn(resolvedCtor.declaringType().getQualifiedName());
      String calleeSig =
          JavaTypeNames.buildInitCallSig(typeFqn, resolvedCtor.getQualifiedSignature());
      cypher.run(
          Cypher.CYPHER_UPSERT_CALL, Map.of(Params.CALLER, callerSig, Params.CALLEE, calleeSig));
    } catch (Exception _) {
      JavaTypeNames.resolveOrInferFqn(creation.getType())
          .ifPresent(
              fqn ->
                  cypher.run(
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
      String typeFqn =
          JavaTypeNames.normalizeNestedFqn(resolvedCtor.declaringType().getQualifiedName());
      String calleeSig =
          JavaTypeNames.buildInitCallSig(typeFqn, resolvedCtor.getQualifiedSignature());
      cypher.run(
          Cypher.CYPHER_UPSERT_CALL, Map.of(Params.CALLER, callerSig, Params.CALLEE, calleeSig));
    } catch (Exception _) {
      if (stmt.isThis()) {
        cypher.run(
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
      JavaTypeNames.resolveOrInferFqn(type)
          .ifPresent(
              fqn ->
                  cypher.run(
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
      String paramKey, String paramValue, NodeWithAnnotations<?> node, String query) {
    node.getAnnotations()
        .forEach(
            ann -> {
              String annotFqn;
              try {
                annotFqn = JavaTypeNames.normalizeNestedFqn(ann.resolve().getQualifiedName());
              } catch (Exception _) {
                annotFqn = ann.getNameAsString();
              }
              cypher.run(
                  query,
                  Map.of(
                      paramKey,
                      paramValue,
                      Params.ANNOT_FQN,
                      annotFqn,
                      Params.ANNOT_NAME,
                      JavaTypeNames.nameFromFqn(annotFqn)));
            });
  }
}
