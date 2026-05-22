package io.github.ousatov.tools.memgraph.exe;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
  private static final String JAVA_LANGUAGE = SourceLanguage.JAVA.graphName();
  private static final String JAVASCRIPT_LANGUAGE = SourceLanguage.JAVASCRIPT.graphName();

  private final CypherExecutor cypher;
  private final CallEdgeWriter callEdges;

  /**
   * @param session Bolt session — must not be shared with other threads
   * @param project project name used to scope all Cypher operations
   */
  public GraphWriter(Session session, String project) {
    this.cypher = new CypherExecutor(session, project);
    this.callEdges = new CallEdgeWriter(cypher);
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
   * Rolls back the current file-level transaction and clears it. Called on any writing failure to
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

  /** Resolves deferred owner/name call records after all in-project methods are available. */
  public void resolvePendingCalls() {
    cypher.run(Cypher.CYPHER_RESOLVE_PENDING_CALLS, Map.of());
  }

  /** Removes stale deferred owner/name call records for methods declared by one source file. */
  public void deletePendingCallsForFile(Path file) {
    cypher.run(Cypher.CYPHER_DELETE_PENDING_CALLS_FOR_FILE, Map.of(Params.PATH, file.toString()));
  }

  /**
   * Removes JS/TS definitions for {@code file} that were written under an older module FQN scheme.
   */
  public void deleteStaleJavascriptDefinitionsForFile(Path file, String currentModuleFqn) {
    Map<String, Object> params =
        Map.of(
            Params.PATH,
            file.toString(),
            Params.FQN,
            currentModuleFqn,
            Params.MODULE_PREFIX,
            currentModuleFqn + ".");
    cypher.run(Cypher.CYPHER_DELETE_STALE_JAVASCRIPT_MEMBERS_FOR_FILE, params);
    cypher.run(Cypher.CYPHER_DELETE_STALE_JAVASCRIPT_OWNERS_FOR_FILE, params);
    cypher.run(Cypher.CYPHER_DELETE_EMPTY_JAVASCRIPT_PACKAGES, Map.of());
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
    upsertFile(file, JAVA_LANGUAGE);
  }

  /** Upserts a {@code :File} node and records the source language that produced it. */
  public void upsertFile(Path file, String language) {
    long lastModified;
    try {
      lastModified = Files.getLastModifiedTime(file).toMillis();
    } catch (IOException _) {
      lastModified = -1L;
    }
    cypher.run(
        Cypher.CYPHER_UPSERT_FILE,
        Map.of(
            Params.PATH,
            file.toString(),
            Params.LAST_MODIFIED,
            lastModified,
            Params.LANGUAGE,
            language));
  }

  /** Upserts a {@code :Package} node and links it to the code anchor. */
  public void upsertPackage(String pkg) {
    cypher.run(Cypher.CYPHER_UPSERT_PACKAGE, Map.of(Params.NAME, pkg));
  }

  /** Upserts the synthetic module owner used for top-level JavaScript declarations. */
  public void upsertJavascriptModule(
      Path file,
      String pkg,
      String fqn,
      String name,
      String modulePath,
      int startLine,
      int endLine) {
    upsertClassNode(
        file,
        pkg,
        fqn,
        name,
        false,
        "",
        false,
        false,
        false,
        JAVASCRIPT_LANGUAGE,
        "module",
        modulePath,
        "");
    upsertMethodNode(
        new Method(
            fqn,
            fqn + "." + Labels.INIT + "()",
            Labels.INIT,
            Labels.VOID,
            true,
            "",
            startLine,
            endLine,
            true,
            JAVASCRIPT_LANGUAGE,
            "module"));
  }

  /** Upserts a JavaScript/TypeScript class declaration using the existing {@code :Class} label. */
  @SuppressWarnings("java:S107")
  public void upsertJavascriptClass(
      Path file,
      String pkg,
      String fqn,
      String name,
      String modulePath,
      String framework,
      boolean isAbstract,
      boolean hasDeclaredConstructor,
      int startLine,
      int endLine) {
    upsertJavascriptTypeClass(
        file,
        pkg,
        fqn,
        name,
        modulePath,
        framework,
        false,
        isAbstract,
        "class",
        startLine,
        endLine);
    if (!hasDeclaredConstructor) {
      upsertMethodNode(
          new Method(
              fqn,
              fqn + "." + Labels.INIT + "()",
              Labels.INIT,
              Labels.VOID,
              false,
              "",
              startLine,
              endLine,
              true,
              JAVASCRIPT_LANGUAGE,
              "constructor"));
    }
  }

  /** Upserts a TypeScript enum using the existing {@code :Class} label and enum metadata. */
  public void upsertJavascriptEnum(
      Path file,
      String pkg,
      String fqn,
      String name,
      String modulePath,
      int startLine,
      int endLine) {
    upsertJavascriptTypeClass(
        file, pkg, fqn, name, modulePath, "", true, false, "enum", startLine, endLine);
  }

  @SuppressWarnings("java:S107")
  private void upsertJavascriptTypeClass(
      Path file,
      String pkg,
      String fqn,
      String name,
      String modulePath,
      String framework,
      boolean isEnum,
      boolean isAbstract,
      String kind,
      int startLine,
      int endLine) {
    upsertClassNode(
        file,
        pkg,
        fqn,
        name,
        isAbstract,
        "",
        isEnum,
        false,
        false,
        JAVASCRIPT_LANGUAGE,
        kind,
        modulePath,
        framework);
  }

  /** Writes a JavaScript/TypeScript class {@code EXTENDS} relation. */
  public void upsertJavascriptExtendsClass(String childFqn, String parentFqn) {
    upsertTypeRelation(
        Cypher.CYPHER_UPSERT_EXTENDS_CLASS,
        childFqn,
        parentFqn,
        Params.PARENT,
        Params.PARENT_NAME,
        Params.PARENT_PKG);
  }

  /** Writes a JavaScript/TypeScript interface {@code EXTENDS} relation. */
  public void upsertJavascriptInterfaceExtends(String childFqn, String parentFqn) {
    upsertTypeRelation(
        Cypher.CYPHER_UPSERT_INTERFACE_EXTENDS,
        childFqn,
        parentFqn,
        Params.PARENT,
        Params.PARENT_NAME,
        Params.PARENT_PKG);
  }

  /** Writes a JavaScript/TypeScript class {@code IMPLEMENTS} relation. */
  public void upsertJavascriptImplements(String childFqn, String interfaceFqn) {
    upsertTypeRelation(
        Cypher.CYPHER_UPSERT_IMPLEMENTS,
        childFqn,
        interfaceFqn,
        Params.IFACE,
        Params.IFACE_NAME,
        Params.IFACE_PKG);
  }

  /** Upserts a TypeScript interface or type alias using the compatible {@code :Interface} label. */
  public void upsertJavascriptInterface(
      Path file,
      String pkg,
      String fqn,
      String name,
      String kind,
      String modulePath,
      String framework) {
    upsertInterfaceNode(
        file, pkg, fqn, name, true, "", JAVASCRIPT_LANGUAGE, kind, modulePath, framework);
  }

  /** Upserts a JavaScript/TypeScript property or top-level variable as a {@code :Field}. */
  public void upsertJavascriptField(
      String ownerFqn, String fqn, String name, String type, boolean isStatic, String kind) {
    cypher.run(
        Cypher.CYPHER_UPSERT_FIELD,
        Map.of(
            Params.FQN,
            fqn,
            Params.NAME,
            name,
            Params.TYPE,
            type,
            Params.IS_STATIC,
            isStatic,
            Params.VISIBILITY,
            "",
            Params.LANGUAGE,
            JAVASCRIPT_LANGUAGE,
            Params.KIND,
            kind,
            Params.OWNER,
            ownerFqn));
  }

  /** Upserts a JavaScript/TypeScript function or method as a {@code :Method}. */
  @SuppressWarnings("java:S107")
  public void upsertJavascriptMethod(
      String ownerFqn,
      String signature,
      String name,
      String returnType,
      boolean isStatic,
      int startLine,
      int endLine,
      String kind) {
    upsertMethodNode(
        new Method(
            ownerFqn,
            signature,
            name,
            returnType,
            isStatic,
            "",
            startLine,
            endLine,
            false,
            JAVASCRIPT_LANGUAGE,
            kind));
  }

  /** Adds an annotation/decorator edge for a type or field identified by FQN. */
  public void upsertAnnotationReferenceByFqn(String ownerFqn, String annotationFqn, String name) {
    upsertAnnotationReferenceByFqn(ownerFqn, annotationFqn, name, JAVA_LANGUAGE, Params.ANNOTATION);
  }

  /** Adds an annotation/decorator edge for a type or field identified by FQN. */
  public void upsertAnnotationReferenceByFqn(
      String ownerFqn, String annotationFqn, String name, String language, String kind) {
    cypher.run(
        Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_FQN,
        Map.of(
            Params.OWNER,
            ownerFqn,
            Params.ANNOT_FQN,
            annotationFqn,
            Params.ANNOT_NAME,
            name,
            Params.LANGUAGE,
            language,
            Params.KIND,
            kind));
  }

  /** Adds an annotation/decorator edge for a method identified by signature. */
  public void upsertAnnotationReferenceBySig(
      String signature, String annotationFqn, String name, String language, String kind) {
    cypher.run(
        Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_SIG,
        Map.of(
            Params.SIG,
            signature,
            Params.ANNOT_FQN,
            annotationFqn,
            Params.ANNOT_NAME,
            name,
            Params.LANGUAGE,
            language,
            Params.KIND,
            kind));
  }

  /** Upserts a resolved in-project call edge. */
  public void upsertCall(String callerSignature, String calleeSignature) {
    cypher.run(
        Cypher.CYPHER_UPSERT_CALL,
        Map.of(Params.CALLER, callerSignature, Params.CALLEE, calleeSignature));
  }

  /** Stores a deferred owner/name call for post-ingestion resolution. */
  public void upsertPendingCallByName(String callerSignature, String ownerFqn, String calleeName) {
    cypher.run(
        Cypher.CYPHER_UPSERT_PENDING_CALL_BY_NAME,
        Map.of(
            Params.CALLER,
            callerSignature,
            Params.OWNER_FQN,
            ownerFqn,
            Params.CALLEE_NAME,
            calleeName));
  }

  private static String typeFqn(String pkg, String outerFqn, String simpleName) {
    return outerFqn != null ? outerFqn + "$" + simpleName : JavaTypeNames.buildFqn(pkg, simpleName);
  }

  @SuppressWarnings("java:S107")
  private void upsertClassNode(
      Path file,
      String pkg,
      String fqn,
      String name,
      boolean isAbstract,
      String visibility,
      boolean isEnum,
      boolean isRecord,
      boolean isFinal) {
    upsertClassNode(
        file,
        pkg,
        fqn,
        name,
        isAbstract,
        visibility,
        isEnum,
        isRecord,
        isFinal,
        JAVA_LANGUAGE,
        classKind(isEnum, isRecord),
        "",
        "");
  }

  @SuppressWarnings("java:S107")
  private void upsertClassNode(
      Path file,
      String pkg,
      String fqn,
      String name,
      boolean isAbstract,
      String visibility,
      boolean isEnum,
      boolean isRecord,
      boolean isFinal,
      String language,
      String kind,
      String modulePath,
      String framework) {
    cypher.run(
        Cypher.CYPHER_UPSERT_CLASS,
        Map.ofEntries(
            Map.entry(Params.FQN, fqn),
            Map.entry(Params.NAME, name),
            Map.entry(Params.PKG, pkg),
            Map.entry(Params.PATH, file.toString()),
            Map.entry(Params.IS_ABSTRACT, isAbstract),
            Map.entry(Params.VISIBILITY, visibility),
            Map.entry(Params.IS_ENUM, isEnum),
            Map.entry(Params.IS_RECORD, isRecord),
            Map.entry(Params.IS_FINAL, isFinal),
            Map.entry(Params.LANGUAGE, language),
            Map.entry(Params.KIND, kind),
            Map.entry(Params.MODULE_PATH, modulePath),
            Map.entry(Params.FRAMEWORK, framework)));
  }

  private void upsertInterfaceNode(
      Path file, String pkg, String fqn, String name, boolean isAbstract, String visibility) {
    upsertInterfaceNode(
        file, pkg, fqn, name, isAbstract, visibility, JAVA_LANGUAGE, "interface", "", "");
  }

  @SuppressWarnings("java:S107")
  private void upsertInterfaceNode(
      Path file,
      String pkg,
      String fqn,
      String name,
      boolean isAbstract,
      String visibility,
      String language,
      String kind,
      String modulePath,
      String framework) {
    cypher.run(
        Cypher.CYPHER_UPSERT_INTERFACE,
        Map.ofEntries(
            Map.entry(Params.FQN, fqn),
            Map.entry(Params.NAME, name),
            Map.entry(Params.PKG, pkg),
            Map.entry(Params.PATH, file.toString()),
            Map.entry(Params.IS_ABSTRACT, isAbstract),
            Map.entry(Params.VISIBILITY, visibility),
            Map.entry(Params.IS_FINAL, false),
            Map.entry(Params.LANGUAGE, language),
            Map.entry(Params.KIND, kind),
            Map.entry(Params.MODULE_PATH, modulePath),
            Map.entry(Params.FRAMEWORK, framework)));
  }

  private static String classKind(boolean isEnum, boolean isRecord) {
    if (isEnum) {
      return "enum";
    }
    if (isRecord) {
      return "record";
    }
    return "class";
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
   * constants, fields, methods, constructors, implemented interfaces, and nested types.
   */
  public void upsertEnum(Path file, String pkg, EnumDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    upsertClassNode(
        file,
        pkg,
        fqn,
        decl.getNameAsString(),
        false,
        decl.getAccessSpecifier().asString(),
        true,
        false,
        true);
    upsertAnnotationsByFqn(fqn, decl);
    upsertImplementedTypes(fqn, decl);
    decl.getEntries().forEach(entry -> upsertEnumConstant(fqn, entry));
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
    upsertClassNode(
        file,
        pkg,
        fqn,
        decl.getNameAsString(),
        false,
        decl.getAccessSpecifier().asString(),
        false,
        true,
        true);
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
            decl.getAccessSpecifier().asString(),
            Params.LANGUAGE,
            JAVA_LANGUAGE,
            Params.KIND,
            Params.ANNOTATION,
            Params.MODULE_PATH,
            "",
            Params.FRAMEWORK,
            ""));
    upsertAnnotationsByFqn(fqn, decl);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including
   * directly nested types. Call this after all structural upserts for the file are complete, so
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
    String fqn = typeFqn(pkg, outerFqn, decl.getNameAsString());
    decl.getMethods().forEach(m -> callEdges.upsert(JavaTypeNames.buildSignature(fqn, m), fqn, m));
    decl.getConstructors()
        .forEach(c -> callEdges.upsert(JavaTypeNames.buildConstructorSignature(fqn, c), fqn, c));
    nestedClassDeclarationsOf(decl.getMembers())
        .forEach(nested -> upsertTypeCallEdgesInternal(pkg, fqn, nested));
  }

  private void upsertTypeInternal(
      Path file, String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn = typeFqn(pkg, outerFqn, decl.getNameAsString());
    if (decl.isInterface()) {
      upsertInterfaceNode(
          file,
          pkg,
          fqn,
          decl.getNameAsString(),
          decl.isAbstract(),
          decl.getAccessSpecifier().asString());
    } else {
      upsertClassNode(
          file,
          pkg,
          fqn,
          decl.getNameAsString(),
          decl.isAbstract(),
          decl.getAccessSpecifier().asString(),
          false,
          false,
          decl.isFinal());
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
    methods.forEach(m -> callEdges.upsert(JavaTypeNames.buildSignature(fqn, m), fqn, m));
    constructors.forEach(
        c -> callEdges.upsert(JavaTypeNames.buildConstructorSignature(fqn, c), fqn, c));
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
                upsertTypeRelation(
                    extendsCypher, fqn, ext, Params.PARENT, Params.PARENT_NAME, Params.PARENT_PKG));
    decl.getImplementedTypes().forEach(impl -> upsertImplementedType(fqn, impl));
  }

  /** Writes {@code IMPLEMENTS} edges for enums and records that implement interfaces. */
  private void upsertImplementedTypes(String fqn, NodeWithImplements<?> decl) {
    decl.getImplementedTypes().forEach(impl -> upsertImplementedType(fqn, impl));
  }

  private void upsertImplementedType(String fqn, ClassOrInterfaceType iface) {
    upsertTypeRelation(
        Cypher.CYPHER_UPSERT_IMPLEMENTS,
        fqn,
        iface,
        Params.IFACE,
        Params.IFACE_NAME,
        Params.IFACE_PKG);
  }

  private void upsertTypeRelation(
      String query,
      String childFqn,
      String targetFqn,
      String targetParam,
      String targetNameParam,
      String targetPkgParam) {
    if (targetFqn == null || targetFqn.isBlank()) {
      return;
    }
    cypher.run(
        query,
        Map.of(
            Params.CHILD,
            childFqn,
            targetParam,
            targetFqn,
            targetNameParam,
            JavaTypeNames.nameFromFqn(targetFqn),
            targetPkgParam,
            JavaTypeNames.packageFromFqn(targetFqn)));
  }

  private void upsertTypeRelation(
      String query,
      String childFqn,
      ClassOrInterfaceType target,
      String targetParam,
      String targetNameParam,
      String targetPkgParam) {
    JavaTypeNames.withResolvedType(
        target,
        targetFqn ->
            cypher.run(
                query,
                Map.of(
                    Params.CHILD,
                    childFqn,
                    targetParam,
                    targetFqn,
                    targetNameParam,
                    JavaTypeNames.nameFromFqn(targetFqn),
                    targetPkgParam,
                    JavaTypeNames.packageFromFqn(targetFqn))));
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
              Params.LANGUAGE,
              JAVA_LANGUAGE,
              Params.KIND,
              "record-component",
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
                      Params.LANGUAGE,
                      JAVA_LANGUAGE,
                      Params.KIND,
                      "field",
                      Params.OWNER,
                      ownerFqn));
              upsertAnnotationsByFqn(fqn, field);
            });
  }

  private void upsertEnumConstant(String ownerFqn, EnumConstantDeclaration entry) {
    cypher.run(
        Cypher.CYPHER_UPSERT_FIELD,
        Map.of(
            Params.FQN,
            ownerFqn + "#" + entry.getNameAsString(),
            Params.NAME,
            entry.getNameAsString(),
            Params.TYPE,
            ownerFqn,
            Params.IS_STATIC,
            true,
            Params.VISIBILITY,
            "public",
            Params.LANGUAGE,
            JAVA_LANGUAGE,
            Params.KIND,
            "enum-member",
            Params.OWNER,
            ownerFqn));
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
        Map.ofEntries(
            Map.entry(Params.SIG, method.signature()),
            Map.entry(Params.NAME, method.name()),
            Map.entry(Params.RET, method.returnType()),
            Map.entry(Params.IS_STATIC, method.isStatic()),
            Map.entry(Params.VISIBILITY, method.visibility()),
            Map.entry(Params.START, method.startLine()),
            Map.entry(Params.END, method.endLine()),
            Map.entry(Params.OWNER, method.ownerFqn()),
            Map.entry(Params.OWNER_DISPLAY_NAME, JavaTypeNames.nameFromFqn(method.ownerFqn())),
            Map.entry(Params.LANGUAGE, method.language()),
            Map.entry(Params.KIND, method.kind()),
            Map.entry(Params.IS_SYNTHETIC, method.isSynthetic())));
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
                      JavaTypeNames.nameFromFqn(annotFqn),
                      Params.LANGUAGE,
                      JAVA_LANGUAGE,
                      Params.KIND,
                      Params.ANNOTATION));
            });
  }
}
