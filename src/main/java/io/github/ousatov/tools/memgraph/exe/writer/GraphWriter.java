package io.github.ousatov.tools.memgraph.exe.writer;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.AnnotationWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.CallWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.FieldWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.PendingCallWrite;
import io.github.ousatov.tools.memgraph.exe.writer.java.JavaGraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.js.JsGraphWriter;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p>Callers may open an explicit per-file transaction via {@link #beginFileTransaction()} / {@link
 * #commitFileTransaction()} / {@link #rollbackFileTransaction()} so destructive cleanup and
 * replacement writes commit atomically for one source file.
 *
 * @author Oleksii Usatov
 */
public final class GraphWriter {

  private static final Logger log = LoggerFactory.getLogger(GraphWriter.class);

  private static final int WIPE_BATCH_SIZE = 10_000;
  private static final String JAVA_LANGUAGE = SourceLanguage.JAVA.graphName();

  private final CypherExecutor cypher;
  private final CallEdgeWriter callEdges;
  private final GraphNodeWriter nodes;
  private final JavaGraphWriter javaWriter;
  private final JsGraphWriter jsWriter;
  private final IngestionRunStats stats;
  private List<String> retainedSourcePaths = List.of();

  /**
   * @param session Bolt session — must not be shared with other threads
   * @param project project name used to scope all Cypher operations
   */
  public GraphWriter(Session session, String project) {
    this(session, project, new IngestionRunStats(0));
  }

  /**
   * @param session Bolt session — must not be shared with other threads
   * @param project project name used to scope all Cypher operations
   * @param stats optional run-level counters shared by the orchestrator
   */
  public GraphWriter(Session session, String project, IngestionRunStats stats) {
    this.cypher = new CypherExecutor(session, project, stats);
    this.nodes = new GraphNodeWriter(cypher);
    this.callEdges = new CallEdgeWriter(cypher, nodes);
    var languageWriters = new CommonGraphWriter.Dependencies(cypher, callEdges, nodes);
    this.javaWriter = new JavaGraphWriter(languageWriters);
    this.jsWriter = new JsGraphWriter(languageWriters);
    this.stats = stats;
  }

  /** Sets project paths that should preserve shared definitions during file cleanup. */
  public void setRetainedSourcePaths(Collection<Path> files) {
    retainedSourcePaths = files.stream().map(Path::toString).toList();
  }

  /**
   * Opens an explicit Bolt transaction so that all subsequent Cypher writes for a source file
   * commit or roll back together.
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

  public static boolean isRetryable(RuntimeException e) {
    return CypherExecutor.isRetryable(e);
  }

  public void recordIngestedFile() {
    stats.recordIngestedFile();
  }

  public void recordSkippedFile() {
    stats.recordSkippedFile();
  }

  public void recordFailedFile() {
    stats.recordFailedFile();
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
    cypher.run(
        Cypher.CYPHER_DELETE_PENDING_CALLS_FOR_FILE,
        Map.of(Params.PATH, file.toString(), Params.PATHS, retainedSourcePaths));
  }

  /**
   * Prunes graph state for declarations and file-local relationships that disappeared from a source
   * file.
   *
   * <p>Current method nodes are kept so incoming {@code CALLS} edges from unchanged files survive
   * incremental re-ingestion. Their outgoing edges are cleared and recreated from the current file
   * body. Current owners are refreshed by FQN before file-local stale-owner cleanup so a
   * declaration moved from another file does not carry stale members, annotations, or type
   * relations forward.
   */
  public void deleteStaleDefinitionsForFile(Path file, SourceFileDefinitions definitions) {
    deletePendingCallsForFile(file);
    Map<String, Object> params =
        Map.ofEntries(
            Map.entry(Params.PATH, file.toString()),
            Map.entry(Params.CLASS_FQNS, definitions.classFqns()),
            Map.entry(Params.INTERFACE_FQNS, definitions.interfaceFqns()),
            Map.entry(Params.ANNOTATION_FQNS, definitions.annotationFqns()),
            Map.entry(Params.METHOD_SIGNATURES, definitions.methodSignatures()),
            Map.entry(Params.FIELD_FQNS, definitions.fieldFqns()),
            Map.entry(Params.PATHS, retainedSourcePaths));
    List.of(
            Cypher.CYPHER_DELETE_CURRENT_OWNER_CALLS_FOR_FILE,
            Cypher.CYPHER_DELETE_CURRENT_OWNER_ANNOTATIONS_FOR_FILE,
            Cypher.CYPHER_DELETE_CURRENT_MEMBER_ANNOTATIONS_FOR_FILE,
            Cypher.CYPHER_DELETE_CURRENT_TYPE_RELATIONS_FOR_FILE,
            Cypher.CYPHER_DELETE_STALE_CURRENT_OWNER_MEMBERS_FOR_FILE,
            Cypher.CYPHER_DELETE_STALE_OWNER_MEMBERS_FOR_FILE,
            Cypher.CYPHER_DELETE_STALE_OWNERS_FOR_FILE,
            Cypher.CYPHER_DELETE_CALLS_FOR_FILE,
            Cypher.CYPHER_DELETE_OWNER_ANNOTATIONS_FOR_FILE,
            Cypher.CYPHER_DELETE_MEMBER_ANNOTATIONS_FOR_FILE,
            Cypher.CYPHER_DELETE_TYPE_RELATIONS_FOR_FILE,
            Cypher.CYPHER_DELETE_STALE_METHODS_FOR_FILE,
            Cypher.CYPHER_DELETE_STALE_FIELDS_FOR_FILE)
        .forEach(q -> cypher.run(q, params));
  }

  /** Deletes all graph state owned by a source file that no longer exists. */
  public void deleteSourceFile(Path file) {
    runInFileTransaction(
        () -> {
          deletePendingCallsForFile(file);
          List.of(
                  Cypher.CYPHER_DELETE_MEMBERS_FOR_FILE,
                  Cypher.CYPHER_DELETE_OWNERS_FOR_FILE,
                  Cypher.CYPHER_DELETE_FILE,
                  Cypher.CYPHER_DELETE_EMPTY_PACKAGES)
              .forEach(q -> cypher.run(q, Map.of(Params.PATH, file.toString())));
        });
  }

  /** Deletes file graph state for language-specific files absent from the current source tree. */
  public void deleteFilesMissingFromSource(
      Path sourceRoot, Collection<Path> files, SourceLanguage language) {
    deleteFilesMissingFromSource(sourceRoot, files, files, language);
  }

  /**
   * Deletes language-specific files absent from the current source tree while preserving
   * definitions still retained by any active project file.
   */
  public void deleteFilesMissingFromSource(
      Path sourceRoot,
      Collection<Path> files,
      Collection<Path> retainedFiles,
      SourceLanguage language) {
    String sourceRootText = sourceRoot.toString();
    String separator = sourceRoot.getFileSystem().getSeparator();
    String sourceRootPrefix =
        sourceRootText.endsWith(separator) ? sourceRootText : sourceRootText + separator;
    Map<String, Object> params =
        Map.of(
            Params.PATHS,
            files.stream().map(Path::toString).toList(),
            Params.RETAINED_PATHS,
            retainedFiles.stream().map(Path::toString).toList(),
            Params.SOURCE_ROOT,
            sourceRootText,
            Params.SOURCE_ROOT_PREFIX,
            sourceRootPrefix,
            Params.LANGUAGE,
            language.graphName());
    runInFileTransaction(
        () ->
            List.of(
                    Cypher.CYPHER_DELETE_MISSING_FILE_PENDING_CALLS,
                    Cypher.CYPHER_DELETE_MISSING_FILE_MEMBERS,
                    Cypher.CYPHER_DELETE_MISSING_FILE_OWNERS,
                    Cypher.CYPHER_DELETE_MISSING_FILES,
                    Cypher.CYPHER_DELETE_EMPTY_PACKAGES)
                .forEach(q -> cypher.run(q, params)));
  }

  /** Deletes package nodes that no longer contain any code declarations. */
  public void deleteEmptyPackages() {
    cypher.run(Cypher.CYPHER_DELETE_EMPTY_PACKAGES, Map.of());
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
            Cypher.CYPHER_CLEAR_CODE_PACKAGE_CODE_REF_RESOLUTIONS,
            Cypher.CYPHER_RESOLVE_JAVA_CODE_REFS_CODE,
            Cypher.CYPHER_RESOLVE_JAVA_CODE_REFS_PACKAGE,
            Cypher.CYPHER_RESOLVE_JAVASCRIPT_CODE_REFS_CODE,
            Cypher.CYPHER_RESOLVE_JAVASCRIPT_CODE_REFS_PACKAGE,
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
   * @param language source language for the files
   * @return map of {@code path.toString()} → stored epoch-millis
   */
  public Map<String, Long> getAllFileLastModified(List<Path> files, SourceLanguage language) {
    List<String> paths = files.stream().map(Path::toString).toList();
    try {
      return cypher.read(
          getFilesLastModifiedCypher(language),
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

  /** Returns project file paths outside the active source root that must remain retained. */
  public Set<Path> getRetainedFilePathsOutsideSourceRoot(Path sourceRoot) {
    String sourceRootText = sourceRoot.toString();
    String separator = sourceRoot.getFileSystem().getSeparator();
    String sourceRootPrefix =
        sourceRootText.endsWith(separator) ? sourceRootText : sourceRootText + separator;
    return cypher.read(
        Cypher.CYPHER_GET_RETAINED_FILES_OUTSIDE_SOURCE_ROOT,
        Map.of(Params.SOURCE_ROOT, sourceRootText, Params.SOURCE_ROOT_PREFIX, sourceRootPrefix),
        result -> {
          Set<Path> retained = new HashSet<>();
          while (result.hasNext()) {
            String path = result.next().get(Params.PATH).asString(null);
            if (path != null) {
              retained.add(Path.of(path));
            }
          }
          return retained;
        });
  }

  /** Returns project file paths under the active source root. */
  public Set<Path> getFilePathsInSourceRoot(Path sourceRoot) {
    return getFilePathsInSourceRoot(sourceRoot, Cypher.CYPHER_GET_FILES_IN_SOURCE_ROOT, Map.of());
  }

  /** Returns language-specific project file paths under the active source root. */
  public Set<Path> getFilePathsInSourceRoot(Path sourceRoot, SourceLanguage language) {
    return getFilePathsInSourceRoot(
        sourceRoot,
        getFilesInSourceRootCypher(language),
        Map.of(Params.LANGUAGE, language.graphName()));
  }

  private Set<Path> getFilePathsInSourceRoot(
      Path sourceRoot, String query, Map<String, Object> extraParams) {
    String sourceRootText = sourceRoot.toString();
    String separator = sourceRoot.getFileSystem().getSeparator();
    String sourceRootPrefix =
        sourceRootText.endsWith(separator) ? sourceRootText : sourceRootText + separator;
    Map<String, Object> params = new HashMap<>(extraParams);
    params.put(Params.SOURCE_ROOT, sourceRootText);
    params.put(Params.SOURCE_ROOT_PREFIX, sourceRootPrefix);
    return cypher.read(
        query,
        params,
        result -> {
          Set<Path> paths = new HashSet<>();
          while (result.hasNext()) {
            String path = result.next().get(Params.PATH).asString(null);
            if (path != null) {
              paths.add(Path.of(path));
            }
          }
          return paths;
        });
  }

  /** Returns the stored source-root reconstruction hint for {@code file}, when available. */
  public Optional<String> getSourceRootHint(Path file, SourceLanguage language) {
    return cypher.read(
        getSourceRootHintCypher(language),
        Map.of(Params.PATH, file.toString()),
        result -> {
          if (!result.hasNext()) {
            return Optional.empty();
          }
          String hint = result.next().get(Params.SOURCE_ROOT_HINT).asString(null);
          return hint == null ? Optional.empty() : Optional.of(hint);
        });
  }

  /** Returns retained files that share declarations with {@code file}. */
  public Set<Path> getRetainedFilePathsSharingDefinitionsWith(Path file) {
    return cypher.read(
        Cypher.CYPHER_GET_RETAINED_FILES_SHARING_DEFINITIONS_WITH_FILE,
        Map.of(Params.PATH, file.toString(), Params.PATHS, retainedSourcePaths),
        result -> {
          Set<Path> retained = new HashSet<>();
          while (result.hasNext()) {
            String path = result.next().get(Params.PATH).asString(null);
            if (path != null) {
              retained.add(Path.of(path));
            }
          }
          return retained;
        });
  }

  private void runInFileTransaction(Runnable action) {
    beginFileTransaction();
    try {
      action.run();
      commitFileTransaction();
    } catch (RuntimeException e) {
      rollbackFileTransaction();
      throw e;
    }
  }

  private static String getFilesLastModifiedCypher(SourceLanguage language) {
    return switch (language) {
      case JAVA -> Cypher.CYPHER_GET_JAVA_FILES_LAST_MODIFIED;
      case JAVASCRIPT -> Cypher.CYPHER_GET_JAVASCRIPT_FILES_LAST_MODIFIED;
    };
  }

  private static String getFilesInSourceRootCypher(SourceLanguage language) {
    return switch (language) {
      case JAVA -> Cypher.CYPHER_GET_JAVA_FILES_IN_SOURCE_ROOT;
      case JAVASCRIPT -> Cypher.CYPHER_GET_JAVASCRIPT_FILES_IN_SOURCE_ROOT;
    };
  }

  private static String getSourceRootHintCypher(SourceLanguage language) {
    return switch (language) {
      case JAVA -> Cypher.CYPHER_GET_JAVA_SOURCE_ROOT_HINT_FOR_FILE;
      case JAVASCRIPT -> Cypher.CYPHER_GET_JAVASCRIPT_SOURCE_ROOT_HINT_FOR_FILE;
    };
  }

  /** Creates or refreshes all supported code-language roots and the {@code :Memory} anchor. */
  public void upsertProject(Path sourceRoot) {
    upsertProject(sourceRoot, SourceLanguage.supported());
  }

  /** Creates or refreshes selected {@code :Project -> :Language -> :Code} roots. */
  public void upsertProject(Path sourceRoot, List<SourceLanguage> languages) {
    for (SourceLanguage language : languages) {
      cypher.run(
          Cypher.CYPHER_UPSERT_PROJECT,
          Map.of(
              "sourceRoot",
              sourceRoot.toString(),
              Params.LANGUAGE,
              language.graphName(),
              Params.LANGUAGE_NAME,
              language.nodeName()));
    }
  }

  /** Backfills method owner metadata for graphs ingested before owner properties existed. */
  public void backfillMethodOwnerMetadata() {
    cypher.run(Cypher.CYPHER_BACKFILL_METHOD_OWNER_METADATA, Map.of());
  }

  /** Upserts a {@code :File} node and links it to the code anchor. */
  public void upsertFile(Path file) {
    upsertFile(file, SourceLanguage.JAVA);
  }

  /** Upserts a {@code :File} node and links it under the language-specific code anchor. */
  public void upsertFile(Path file, SourceLanguage language) {
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
            language.graphName(),
            Params.LANGUAGE_NAME,
            language.nodeName()));
  }

  /** Upserts a {@code :Package} node and links it to the code anchor. */
  public void upsertPackage(String pkg) {
    upsertPackage(pkg, SourceLanguage.JAVA);
  }

  /** Upserts a {@code :Package} node under the language-specific code anchor. */
  public void upsertPackage(String pkg, SourceLanguage language) {
    cypher.run(
        Cypher.CYPHER_UPSERT_PACKAGE,
        Map.of(
            Params.NAME,
            pkg,
            Params.LANGUAGE,
            language.graphName(),
            Params.LANGUAGE_NAME,
            language.nodeName()));
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
    jsWriter.upsertModule(file, pkg, fqn, name, modulePath, startLine, endLine);
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
    jsWriter.upsertClass(
        file,
        pkg,
        fqn,
        name,
        modulePath,
        framework,
        isAbstract,
        hasDeclaredConstructor,
        startLine,
        endLine);
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
    jsWriter.upsertEnum(file, pkg, fqn, name, modulePath, startLine, endLine);
  }

  /** Writes a JavaScript/TypeScript class {@code EXTENDS} relation. */
  public void upsertJavascriptExtendsClass(String childFqn, String parentFqn) {
    jsWriter.upsertExtendsClass(childFqn, parentFqn);
  }

  /** Writes a JavaScript/TypeScript interface {@code EXTENDS} relation. */
  public void upsertJavascriptInterfaceExtends(String childFqn, String parentFqn) {
    jsWriter.upsertInterfaceExtends(childFqn, parentFqn);
  }

  /** Writes a JavaScript/TypeScript class {@code IMPLEMENTS} relation. */
  public void upsertJavascriptImplements(String childFqn, String interfaceFqn) {
    jsWriter.upsertImplements(childFqn, interfaceFqn);
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
    jsWriter.upsertInterface(file, pkg, fqn, name, kind, modulePath, framework);
  }

  /** Upserts a JavaScript/TypeScript property or top-level variable as a {@code :Field}. */
  public void upsertJavascriptField(
      Path file,
      String ownerFqn,
      String fqn,
      String name,
      String type,
      boolean isStatic,
      String kind) {
    jsWriter.upsertField(file, ownerFqn, fqn, name, type, isStatic, kind);
  }

  /** Upserts a JavaScript/TypeScript function or method as a {@code :Method}. */
  @SuppressWarnings("java:S107")
  public void upsertJavascriptMethod(
      Path file,
      String ownerFqn,
      String signature,
      String name,
      String returnType,
      boolean isStatic,
      int startLine,
      int endLine,
      String kind) {
    jsWriter.upsertMethod(
        file, ownerFqn, signature, name, returnType, isStatic, startLine, endLine, kind);
  }

  public void upsertJavascriptMembers(
      Path file, Collection<FieldWrite> fields, Collection<Method> methods) {
    jsWriter.upsertMembers(file, fields, methods);
  }

  /** Adds an annotation/decorator edge for a type or field identified by FQN. */
  public void upsertAnnotationReferenceByFqn(String ownerFqn, String annotationFqn, String name) {
    upsertAnnotationReferenceByFqn(ownerFqn, annotationFqn, name, JAVA_LANGUAGE, Params.ANNOTATION);
  }

  /** Adds an annotation/decorator edge for a type or field identified by FQN. */
  public void upsertAnnotationReferenceByFqn(
      String ownerFqn, String annotationFqn, String name, String language, String kind) {
    nodes.upsertAnnotationReferencesByFqn(
        List.of(new AnnotationWrite(ownerFqn, annotationFqn, name, language, kind)));
  }

  /** Adds an annotation/decorator edge for a method identified by signature. */
  public void upsertAnnotationReferenceBySig(
      String signature, String annotationFqn, String name, String language, String kind) {
    nodes.upsertAnnotationReferencesBySig(
        List.of(new AnnotationWrite(signature, annotationFqn, name, language, kind)));
  }

  /** Upserts a resolved in-project call edge. */
  public void upsertCall(String callerSignature, String calleeSignature) {
    nodes.upsertCalls(List.of(new CallWrite(callerSignature, calleeSignature)));
  }

  /** Stores a deferred owner/name call for post-ingestion resolution. */
  public void upsertPendingCallByName(String callerSignature, String ownerFqn, String calleeName) {
    nodes.upsertPendingCallsByName(
        List.of(new PendingCallWrite(callerSignature, ownerFqn, calleeName)));
  }

  public void upsertAnnotationReferencesByFqn(Collection<AnnotationWrite> annotations) {
    nodes.upsertAnnotationReferencesByFqn(annotations);
  }

  public void upsertAnnotationReferencesBySig(Collection<AnnotationWrite> annotations) {
    nodes.upsertAnnotationReferencesBySig(annotations);
  }

  public void upsertCalls(Collection<CallWrite> calls) {
    nodes.upsertCalls(calls);
  }

  public void upsertPendingCallsByName(Collection<PendingCallWrite> calls) {
    nodes.upsertPendingCallsByName(calls);
  }

  /**
   * Upserts a class or interface declaration and all of its members, including directly nested
   * types with their correct {@code $}-separated FQN.
   */
  public void upsertType(Path file, String pkg, ClassOrInterfaceDeclaration decl) {
    javaWriter.upsertType(file, pkg, decl);
  }

  /**
   * Upserts an enum declaration as a {@code :Class} with {@code isEnum = true}, including its
   * constants, fields, methods, constructors, implemented interfaces, and nested types.
   */
  public void upsertEnum(Path file, String pkg, EnumDeclaration decl) {
    javaWriter.upsertEnum(file, pkg, decl);
  }

  /**
   * Upserts a record declaration as a {@code :Class} with {@code isRecord = true}, including its
   * fields, methods, constructors, implemented interfaces, and nested types.
   */
  public void upsertRecord(Path file, String pkg, RecordDeclaration decl) {
    javaWriter.upsertRecord(file, pkg, decl);
  }

  /**
   * Upserts an {@code @interface} declaration as an {@code :Annotation} node, including {@code
   * ANNOTATED_WITH} edges for any meta-annotations applied to it.
   */
  public void upsertAnnotation(Path file, String pkg, AnnotationDeclaration decl) {
    javaWriter.upsertAnnotation(file, pkg, decl);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including
   * directly nested types. Call this after all structural upserts for the file are complete, so
   * every callee node already exists.
   */
  public void upsertTypeCallEdges(String pkg, ClassOrInterfaceDeclaration decl) {
    javaWriter.upsertTypeCallEdges(pkg, decl);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertEnumCallEdges(String pkg, EnumDeclaration decl) {
    javaWriter.upsertEnumCallEdges(pkg, decl);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertRecordCallEdges(String pkg, RecordDeclaration decl) {
    javaWriter.upsertRecordCallEdges(pkg, decl);
  }
}
