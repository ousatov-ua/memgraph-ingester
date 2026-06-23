package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.exe.rag.MemoryChunkBuilder;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.vo.rag.MemorySource;
import io.github.ousatov.tools.memgraph.vo.writer.AnnotationWrite;
import io.github.ousatov.tools.memgraph.vo.writer.CallWrite;
import io.github.ousatov.tools.memgraph.vo.writer.CodeChunkWrite;
import io.github.ousatov.tools.memgraph.vo.writer.EmbeddingProgressListener;
import io.github.ousatov.tools.memgraph.vo.writer.EmbeddingRefreshResult;
import io.github.ousatov.tools.memgraph.vo.writer.PendingCallWrite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.async.AsyncSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes all Cypher upsert operations for a single Bolt session.
 *
 * <p>Each instance wraps exactly one {@link Session}. Parser workers may run in parallel, but graph
 * mutations flow through one writer lane because Memgraph writes cannot be parallelized safely for
 * this workload.
 *
 * <p>Callers may open an explicit per-file transaction via {@link #beginFileTransaction()} / {@link
 * #commitFileTransaction()} / {@link #rollbackFileTransaction()} so destructive cleanup and
 * replacement writes commit atomically for one source file.
 *
 * @author Oleksii Usatov
 */
public final class GraphWriter {

  private static final Logger log = LoggerFactory.getLogger(GraphWriter.class);

  private static final int WIPE_BATCH_SIZE = AppConfig.intValue("writer.wipe-batch-size");
  private static final int MIN_VECTOR_INDEX_CAPACITY =
      AppConfig.intValue("writer.vector-index-min-capacity");
  private static final int VECTOR_INDEX_HEADROOM_MULTIPLIER =
      AppConfig.intValue("writer.vector-index-headroom-multiplier");
  private final CypherExecutor cypher;
  private final GraphNodeWriter nodes;
  private final MemoryChunkBuilder memoryChunks = new MemoryChunkBuilder();
  private final CommonGraphWriter.Dependencies dependencies;
  private final ChunkEmbeddingRefresher embeddingRefresher;
  private final IngestionRunStats stats;
  private final String analysisCacheKey;
  private Set<String> retainedSourcePaths = Set.of();
  private String retainedSourceToken = Const.Symbols.EMPTY;

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
    this(session, project, stats, Const.Symbols.EMPTY);
  }

  /**
   * @param session Bolt session — must not be shared with other threads
   * @param project project name used to scope all Cypher operations
   * @param stats optional run-level counters shared by the orchestrator
   * @param analysisCacheKey analyzer input fingerprint stored on file nodes
   */
  public GraphWriter(
      Session session, String project, IngestionRunStats stats, String analysisCacheKey) {
    this(session, null, project, stats, analysisCacheKey);
  }

  /**
   * @param session Bolt session used for synchronous reads and autocommit writes
   * @param asyncSession optional Bolt async session used to pipeline writes inside transactions;
   *     when {@code null}, transactions fall back to synchronous execution
   * @param project project name used to scope all Cypher operations
   * @param stats optional run-level counters shared by the orchestrator
   * @param analysisCacheKey analyzer input fingerprint stored on file nodes
   */
  public GraphWriter(
      Session session,
      AsyncSession asyncSession,
      String project,
      IngestionRunStats stats,
      String analysisCacheKey) {
    this.cypher = new CypherExecutor(session, asyncSession, project, stats);
    this.nodes = new GraphNodeWriter(cypher);
    CallEdgeWriter callEdges = new CallEdgeWriter(nodes);
    this.dependencies = new CommonGraphWriter.Dependencies(cypher, callEdges, nodes);
    this.embeddingRefresher = new ChunkEmbeddingRefresher(cypher, project);
    this.stats = stats;
    this.analysisCacheKey = analysisCacheKey == null ? Const.Symbols.EMPTY : analysisCacheKey;
  }

  public CommonGraphWriter.Dependencies dependencies() {
    return dependencies;
  }

  /** Sets project paths that should preserve shared definitions during file cleanup. */
  public void setRetainedSourcePaths(Collection<Path> files) {
    Set<String> paths = retainedPathSet(files);
    if (paths.isEmpty()) {
      retainedSourcePaths = Set.of();
      retainedSourceToken = Const.Symbols.EMPTY;
      return;
    }
    if (paths.equals(retainedSourcePaths) && !retainedSourceToken.isBlank()) {
      return;
    }
    retainedSourcePaths = Set.copyOf(paths);
    retainedSourceToken = UUID.randomUUID().toString();
    cypher.run(
        Cypher.CYPHER_MARK_RETAINED_FILES_BATCH,
        Map.of(
            Params.PATHS, List.copyOf(paths), Params.RETAINED_SOURCE_TOKEN, retainedSourceToken));
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

  /** Returns the run-level statistics object for this writer's session. */
  public IngestionRunStats stats() {
    return stats;
  }

  /** Deletes the project-scoped {@code :Code} graph in batches, keeping the {@code :Project}. */
  public void wipe() {
    long deleted;
    do {
      deleted =
          cypher.runCount(
              Cypher.CYPHER_WIPE_PROJECT_CODE_BATCH,
              Const.Params.BATCH_SIZE,
              WIPE_BATCH_SIZE,
              Const.Params.DELETED);
    } while (deleted > 0);
  }

  /** Deletes the project-scoped {@code :Memory} graph while keeping the {@code :Project} anchor. */
  public void wipeMemories() {
    cypher.run(Cypher.CYPHER_WIPE_PROJECT_MEMORIES, Map.of());
  }

  /** Deletes project-scoped derived {@code :CodeChunk} rows in batches. */
  public void wipeCodeRag() {
    long deleted;
    do {
      deleted =
          cypher.runCount(
              Cypher.CYPHER_WIPE_CODE_RAG_BATCH,
              Const.Params.BATCH_SIZE,
              WIPE_BATCH_SIZE,
              Const.Params.DELETED);
    } while (deleted > 0);
  }

  /** Deletes project-scoped derived {@code :MemoryChunk} rows in batches. */
  public void wipeMemoryRag() {
    long deleted;
    do {
      deleted =
          cypher.runCount(
              Cypher.CYPHER_WIPE_MEMORY_RAG_BATCH,
              Const.Params.BATCH_SIZE,
              WIPE_BATCH_SIZE,
              Const.Params.DELETED);
    } while (deleted > 0);
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
    cypher.run(Cypher.CYPHER_RESOLVE_PENDING_CALLS_DIRECT, Map.of());
    cypher.run(Cypher.CYPHER_RESOLVE_PENDING_CALLS, Map.of());
  }

  /** Resolves deferred owner/name call records touched by successful file writes. */
  public void resolvePendingCallsForChangedDefinitions() {
    List<String> callerSignatures = stats.changedCallerSignatures();
    List<String> methodNames = stats.changedMethodNames();
    List<String> ownerFqns = stats.changedOwnerFqns();
    if (callerSignatures.isEmpty() && methodNames.isEmpty() && ownerFqns.isEmpty()) {
      return;
    }
    Map<String, Object> params =
        Map.of(
            Params.CALLER_SIGNATURES,
            callerSignatures,
            Params.METHOD_NAMES,
            methodNames,
            Params.OWNER_FQNS,
            ownerFqns);
    cypher.run(Cypher.CYPHER_RESOLVE_PENDING_CALLS_SCOPED_DIRECT, params);
    cypher.run(Cypher.CYPHER_RESOLVE_PENDING_CALLS_SCOPED, params);
  }

  /** Removes stale deferred owner/name call records for methods declared by one source file. */
  public void deletePendingCallsForFile(Path file) {
    cypher.run(
        Cypher.CYPHER_DELETE_PENDING_CALLS_FOR_FILE,
        Map.of(Params.PATH, file.toString(), Params.RETAINED_SOURCE_TOKEN, retainedSourceToken));
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
  public List<String> deleteStaleDefinitionsForFile(Path file, SourceFileDefinitions definitions) {
    Map<String, Object> params = staleDefinitionParams(file, definitions);
    List<String> deletedMethodNames = deletedSourceFileMethodNames(params);
    deleteStaleDefinitionsForFile(params);
    return deletedMethodNames;
  }

  private void deleteStaleDefinitionsForFile(Map<String, Object> params) {
    cypher.run(Cypher.CYPHER_DELETE_STALE_DEFINITION_RELATIONS_FOR_FILE, params);
    cypher.run(Cypher.CYPHER_DELETE_STALE_DEFINITIONS_FOR_FILE, params);
  }

  private Map<String, Object> staleDefinitionParams(Path file, SourceFileDefinitions definitions) {
    return Map.ofEntries(
        Map.entry(Params.PATH, file.toString()),
        Map.entry(Params.CLASS_FQNS, definitions.classFqns()),
        Map.entry(Params.INTERFACE_FQNS, definitions.interfaceFqns()),
        Map.entry(Params.ANNOTATION_FQNS, definitions.annotationFqns()),
        Map.entry(Params.METHOD_SIGNATURES, definitions.methodSignatures()),
        Map.entry(Params.FIELD_FQNS, definitions.fieldFqns()),
        Map.entry(Params.RETAINED_SOURCE_TOKEN, retainedSourceToken));
  }

  /** Deletes all graph state owned by a source file that no longer exists. */
  public void deleteSourceFile(Path file) {
    Map<String, Object> params = staleDefinitionParams(file, SourceFileDefinitions.empty());
    List<String> deletedMethodNames = deletedSourceFileMethodNames(params);
    runInFileTransaction(
        () -> {
          deleteStaleDefinitionsForFile(params);
          deleteCodeChunksForFile(file);
          List.of(Cypher.CYPHER_DELETE_FILE, Cypher.CYPHER_DELETE_EMPTY_PACKAGES)
              .forEach(q -> cypher.runAndFlush(q, Map.of(Params.PATH, file.toString())));
        });
    stats.recordDeletedMethodNames(deletedMethodNames);
  }

  /** Deletes file graph state for language-specific files absent from the current source tree. */
  public void deleteFilesMissingFromSource(
      Path sourceRoot, Collection<Path> missingFiles, SourceLanguage language) {
    deleteFilesMissingFromSource(sourceRoot, missingFiles, List.of(), language);
  }

  /**
   * Deletes language-specific files absent from the current source tree while preserving
   * definitions still retained by any active project file.
   */
  public void deleteFilesMissingFromSource(
      Path sourceRoot,
      Collection<Path> missingFiles,
      Collection<Path> retainedFiles,
      SourceLanguage language) {
    if (missingFiles.isEmpty()) {
      return;
    }
    setRetainedSourcePathsIfNeeded(retainedFiles);
    Map<String, Object> params = new HashMap<>();
    params.put(Params.PATHS, missingFiles.stream().map(Path::toString).toList());
    params.put(Params.RETAINED_SOURCE_TOKEN, retainedSourceToken);
    params.put(Params.LANGUAGE, language.graphName());
    List<String> deletedMethodNames = deletedMissingFileMethodNames(params);
    runInFileTransaction(
        () ->
            List.of(
                    Cypher.CYPHER_DELETE_MISSING_FILE_PENDING_CALLS,
                    Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILES,
                    Cypher.CYPHER_DELETE_MISSING_FILE_MEMBERS,
                    Cypher.CYPHER_DELETE_MISSING_FILE_OWNERS,
                    Cypher.CYPHER_DELETE_MISSING_FILES,
                    Cypher.CYPHER_DELETE_EMPTY_PACKAGES)
                .forEach(q -> cypher.run(q, params)));
    stats.recordDeletedMethodNames(deletedMethodNames);
  }

  /** Deletes package nodes that no longer contain any code declarations. */
  public void deleteEmptyPackages() {
    cypher.run(Cypher.CYPHER_DELETE_EMPTY_PACKAGES, Map.of());
  }

  /**
   * Removes module-FQN-keyed declarations for {@code file} that were written under an older naming
   * scheme. No-op for languages that do not key definitions by module FQN (currently only JS/TS and
   * Python use this cleanup path).
   */
  public void deleteStaleModuleDefinitionsForFile(
      Path file, String currentModuleFqn, SourceLanguage language) {
    language
        .staleModuleDefinitionsCypher()
        .ifPresent(
            queries -> {
              Map<String, Object> params =
                  Map.of(
                      Params.PATH,
                      file.toString(),
                      Params.FQN,
                      currentModuleFqn,
                      Params.MODULE_PREFIX,
                      currentModuleFqn + Const.Symbols.DOT);
              cypher.run(queries.members(), params);
              cypher.run(queries.owners(), params);
            });
  }

  /** Refreshes {@code :CodeRef} resolution edges to the current project-scoped code graph. */
  public void resolveCodeRefs() {
    cypher.run(Cypher.CYPHER_RESOLVE_CODE_REFS_CODE, Map.of());
    cypher.run(Cypher.CYPHER_RESOLVE_CODE_REFS_PACKAGE, Map.of());
    cypher.run(Cypher.CYPHER_RESOLVE_CODE_REFS_FILE, Map.of());
    cypher.run(Cypher.CYPHER_RESOLVE_CODE_REFS_CLASS, Map.of());
    cypher.run(Cypher.CYPHER_RESOLVE_CODE_REFS_INTERFACE, Map.of());
    cypher.run(Cypher.CYPHER_RESOLVE_CODE_REFS_ANNOTATION, Map.of());
    cypher.run(Cypher.CYPHER_RESOLVE_CODE_REFS_METHOD, Map.of());
    cypher.run(Cypher.CYPHER_RESOLVE_CODE_REFS_FIELD, Map.of());
    cypher.run(Cypher.CYPHER_RESOLVE_CODE_REFS_UNRESOLVED, Map.of());
  }

  /** Refreshes {@code :CodeRef} edges only for code identities touched by this ingestion run. */
  public void resolveCodeRefsForChangedDefinitions() {
    resolveScopedCodeRefs(
        Cypher.CYPHER_RESOLVE_CODE_REFS_CODE_SCOPED,
        Params.CODE_KEYS,
        stats.changedCodeRefCodeKeys());
    resolveScopedCodeRefs(
        Cypher.CYPHER_RESOLVE_CODE_REFS_PACKAGE_SCOPED,
        Params.PACKAGE_KEYS,
        stats.changedCodeRefPackageKeys());
    resolveScopedCodeRefs(
        Cypher.CYPHER_RESOLVE_CODE_REFS_FILE_SCOPED, Params.PATHS, stats.changedCodeRefFilePaths());
    resolveScopedCodeRefs(
        Cypher.CYPHER_RESOLVE_CODE_REFS_CLASS_SCOPED,
        Params.CLASS_FQNS,
        stats.changedCodeRefClassFqns());
    resolveScopedCodeRefs(
        Cypher.CYPHER_RESOLVE_CODE_REFS_INTERFACE_SCOPED,
        Params.INTERFACE_FQNS,
        stats.changedCodeRefInterfaceFqns());
    resolveScopedCodeRefs(
        Cypher.CYPHER_RESOLVE_CODE_REFS_ANNOTATION_SCOPED,
        Params.ANNOTATION_FQNS,
        stats.changedCodeRefAnnotationFqns());
    resolveScopedCodeRefs(
        Cypher.CYPHER_RESOLVE_CODE_REFS_METHOD_SCOPED,
        Params.METHOD_SIGNATURES,
        stats.changedCodeRefMethodSignatures());
    resolveScopedCodeRefs(
        Cypher.CYPHER_RESOLVE_CODE_REFS_FIELD_SCOPED,
        Params.FIELD_FQNS,
        stats.changedCodeRefFieldFqns());
  }

  private void resolveScopedCodeRefs(String query, String paramName, List<String> keys) {
    if (keys.isEmpty()) {
      return;
    }
    cypher.run(query, Map.of(paramName, keys));
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
    if (files.isEmpty()) {
      return Map.of();
    }
    List<String> paths = files.stream().map(Path::toString).toList();
    try {
      return cypher.read(
          language.filesLastModifiedCypher(),
          Map.of(
              Params.PATHS,
              paths,
              Params.LANGUAGE,
              language.graphName(),
              Params.ANALYSIS_CACHE_KEY,
              analysisCacheKey),
          result -> {
            Map<String, Long> mtimes = HashMap.newHashMap(files.size() * 2);
            while (result.hasNext()) {
              var currentRec = result.next();
              String path = currentRec.get(Params.PATH).asString(null);
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

  /** Returns source paths whose graph has no derived {@code :CodeChunk} rows yet. */
  public Set<Path> getFilePathsMissingCodeChunks(List<Path> files) {
    if (files.isEmpty()) {
      return Set.of();
    }
    List<String> paths = files.stream().map(Path::toString).toList();
    return cypher.readPathSet(
        Cypher.CYPHER_GET_FILE_PATHS_MISSING_CODE_CHUNKS, Map.of(Params.PATHS, paths));
  }

  /** Returns project file paths outside the active source root that must remain retained. */
  public Set<Path> getRetainedFilePathsOutsideSourceRoot(Path sourceRoot) {
    return cypher.readPathSet(
        Cypher.CYPHER_GET_RETAINED_FILES_OUTSIDE_SOURCE_ROOT, sourceRootParams(sourceRoot));
  }

  /** Returns project file paths under the active source root. */
  public Set<Path> getFilePathsInSourceRoot(Path sourceRoot) {
    return getFilePathsInSourceRoot(sourceRoot, Cypher.CYPHER_GET_FILES_IN_SOURCE_ROOT, Map.of());
  }

  /** Returns graph languages with file paths under the active source root. */
  public Set<SourceLanguage> getLanguagesInSourceRoot(Path sourceRoot) {
    return cypher.read(
        Cypher.CYPHER_GET_LANGUAGES_IN_SOURCE_ROOT,
        sourceRootParams(sourceRoot),
        result -> {
          Set<SourceLanguage> languages = new HashSet<>();
          while (result.hasNext()) {
            var theRecord = result.next();
            String language = theRecord.get(Params.LANGUAGE).asString(null);
            String languageName = theRecord.get(Params.LANGUAGE_NAME).asString(null);
            if (language != null) {
              languages.add(SourceLanguage.of(language, languageName));
            }
          }
          return languages;
        });
  }

  /** Returns language-specific project file paths under the active source root. */
  public Set<Path> getFilePathsInSourceRoot(Path sourceRoot, SourceLanguage language) {
    return getFilePathsInSourceRoot(
        sourceRoot,
        language.filesInSourceRootCypher(),
        Map.of(Params.LANGUAGE, language.graphName()));
  }

  private Set<Path> getFilePathsInSourceRoot(
      Path sourceRoot, String query, Map<String, Object> extraParams) {
    Map<String, Object> params = new HashMap<>(extraParams);
    params.putAll(sourceRootParams(sourceRoot));
    return cypher.readPathSet(query, params);
  }

  /** Returns the stored source-root reconstruction hint for {@code file}, when available. */
  public Optional<String> getSourceRootHint(Path file, SourceLanguage language) {
    return cypher.read(
        language.sourceRootHintCypher(),
        Map.of(Params.PATH, file.toString(), Params.LANGUAGE, language.graphName()),
        result -> {
          if (!result.hasNext()) {
            return Optional.empty();
          }
          String hint = result.next().get(Params.SOURCE_ROOT_HINT).asString(null);
          return hint == null ? Optional.empty() : Optional.of(hint);
        });
  }

  /** Returns retained paths sharing definitions with any of the given files. */
  public Set<Path> getRetainedFilePathsSharingDefinitionsWith(Collection<Path> files) {
    if (files.isEmpty()) {
      return Set.of();
    }
    List<String> missing = files.stream().map(Path::toString).toList();
    return cypher.readPathSet(
        Cypher.CYPHER_GET_RETAINED_FILES_SHARING_DEFINITIONS_WITH_FILES,
        Map.of(Params.MISSING_PATHS, missing, Params.RETAINED_SOURCE_TOKEN, retainedSourceToken));
  }

  private List<String> deletedSourceFileMethodNames(Map<String, Object> params) {
    return readMethodNames(Cypher.CYPHER_DELETED_SOURCE_FILE_METHOD_NAMES, params);
  }

  private List<String> deletedMissingFileMethodNames(Map<String, Object> params) {
    return readMethodNames(Cypher.CYPHER_DELETED_MISSING_FILE_METHOD_NAMES, params);
  }

  private List<String> readMethodNames(String cypherText, Map<String, Object> params) {
    return cypher.readStringColumn(cypherText, params, Params.NAME).stream()
        .filter(name -> !name.isBlank())
        .filter(name -> !Const.Labels.INIT.equals(name))
        .distinct()
        .toList();
  }

  /** Returns standard source-root parameters (path + path-with-trailing-separator). */
  private static Map<String, Object> sourceRootParams(Path sourceRoot) {
    String sourceRootText = sourceRoot.toString();
    String separator = sourceRoot.getFileSystem().getSeparator();
    String sourceRootPrefix =
        sourceRootText.endsWith(separator) ? sourceRootText : sourceRootText + separator;
    return Map.of(Params.SOURCE_ROOT, sourceRootText, Params.SOURCE_ROOT_PREFIX, sourceRootPrefix);
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

  /** Creates or refreshes selected {@code :Project -> :Language -> :Code} roots. */
  public void upsertProject(Path sourceRoot, List<SourceLanguage> languages) {
    cypher.run(Cypher.CYPHER_UPSERT_PROJECT_ROOT, Map.of());
    for (SourceLanguage language : languages) {
      stats.recordChangedCodeLanguage(language.graphName());
      cypher.run(
          Cypher.CYPHER_UPSERT_PROJECT,
          Map.of(
              Params.SOURCE_ROOT,
              sourceRoot.toString(),
              Params.LANGUAGE,
              language.graphName(),
              Params.LANGUAGE_NAME,
              language.nodeName()));
    }
  }

  /** Creates or refreshes all supported code-language roots and the {@code :Memory} anchor. */
  public void upsertProject(Path sourceRoot) {
    upsertProject(sourceRoot, SourceLanguage.supported());
  }

  /** Backfills method owner metadata for graphs ingested before owner properties existed. */
  public void backfillMethodOwnerMetadata() {
    cypher.run(Cypher.CYPHER_BACKFILL_METHOD_OWNER_METADATA, Map.of());
  }

  /** Removes legacy {@code :Code}-to-{@code :File} links whose language no longer matches. */
  public void deleteLegacyFileCodeLinks() {
    cypher.run(Cypher.CYPHER_DELETE_LEGACY_FILE_CODE_LINKS, Map.of());
  }

  /** Upserts a {@code :File} node and links it under the language-specific code anchor. */
  public void upsertFile(Path file, SourceLanguage language) {
    stats.recordChangedFile(file);
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
            language.nodeName(),
            Params.ANALYSIS_CACHE_KEY,
            analysisCacheKey,
            Params.RETAINED_SOURCE_TOKEN,
            retainedSourceTokenFor(file)));
  }

  private void setRetainedSourcePathsIfNeeded(Collection<Path> files) {
    Set<String> paths = retainedPathSet(files);
    if (!paths.equals(retainedSourcePaths) || (retainedSourceToken.isBlank() && !paths.isEmpty())) {
      setRetainedSourcePaths(files);
    }
  }

  private String retainedSourceTokenFor(Path file) {
    if (retainedSourceToken.isBlank() || !retainedSourcePaths.contains(file.toString())) {
      return Const.Symbols.EMPTY;
    }
    return retainedSourceToken;
  }

  private static Set<String> retainedPathSet(Collection<Path> files) {
    Set<String> paths = new LinkedHashSet<>();
    files.stream().map(Path::toString).forEach(paths::add);
    return paths;
  }

  /** Upserts a {@code :Package} node under the language-specific code anchor. */
  public void upsertPackage(String pkg, SourceLanguage language) {
    stats.recordChangedPackage(language.graphName(), pkg);
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

  /** Replaces the derived {@code :CodeChunk} rows for one source file. */
  public void replaceCodeChunksForFile(Path file, Collection<CodeChunkWrite> chunks) {
    List<String> ids = chunks.stream().map(CodeChunkWrite::id).toList();
    cypher.run(
        Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILE_EXCEPT,
        Map.of(Params.PATH, file.toString(), Params.IDS, ids));
    nodes.upsertCodeChunks(chunks);
  }

  /** Deletes all derived {@code :CodeChunk} rows for one source file. */
  public void deleteCodeChunksForFile(Path file) {
    cypher.runAndFlush(
        Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILE, Map.of(Params.PATH, file.toString()));
  }

  /** Upserts derived {@code :MemoryChunk} rows for current Memory records. */
  public long upsertMemoryChunks() {
    List<MemorySource> sources = memoryChunkSources();
    deleteStaleMemoryChunks();
    List<Map<String, Object>> batch = new ArrayList<>();
    for (MemorySource source : sources) {
      batch.add(memoryChunks.build(source).params());
      if (batch.size() >= WIPE_BATCH_SIZE) {
        cypher.runBatch(Cypher.CYPHER_UPSERT_MEMORY_CHUNKS_BATCH, batch);
        batch.clear();
      }
    }
    cypher.runBatch(Cypher.CYPHER_UPSERT_MEMORY_CHUNKS_BATCH, batch);
    return sources.size();
  }

  private void deleteStaleMemoryChunks() {
    cypher.run(Cypher.CYPHER_DELETE_STALE_MEMORY_CHUNKS, Map.of());
  }

  /**
   * Refreshes {@code :CodeChunk.embedding} values, reporting per-batch progress to {@code
   * listener}.
   */
  public EmbeddingRefreshResult refreshCodeChunkEmbeddings(
      EmbeddingSettings settings, boolean dirtyOnly, EmbeddingProgressListener listener) {
    return embeddingRefresher.refresh(settings, EmbeddingTarget.CODE, dirtyOnly, listener);
  }

  /**
   * Refreshes stale {@code :MemoryChunk.embedding} values, reporting per-batch progress to {@code
   * listener}.
   */
  public EmbeddingRefreshResult refreshMemoryChunkEmbeddings(
      EmbeddingSettings settings, EmbeddingProgressListener listener) {
    return embeddingRefresher.refresh(settings, EmbeddingTarget.MEMORY, false, listener);
  }

  /**
   * Best-effort warm-up of the Memgraph embedding model via a single read-only {@code
   * embeddings.model_info} call. Writes no graph state and never throws.
   *
   * @return the model dimension when the call succeeds, so callers can {@linkplain
   *     #seedEmbeddingDimension seed} the writer that later runs the refresh; empty otherwise
   */
  public OptionalInt warmUpEmbeddingModel(EmbeddingSettings settings) {
    return embeddingRefresher.warmUp(settings);
  }

  /** Caches a known embedding model dimension, e.g. carried over from a prior warm-up. */
  public void seedEmbeddingDimension(EmbeddingSettings settings, int dimension) {
    embeddingRefresher.seedDimension(settings, dimension);
  }

  static int defaultVectorIndexCapacity(long chunkCount) {
    long safeChunkCount = Math.max(1L, chunkCount);
    long withHeadroom =
        safeChunkCount > Integer.MAX_VALUE / VECTOR_INDEX_HEADROOM_MULTIPLIER
            ? Integer.MAX_VALUE
            : safeChunkCount * VECTOR_INDEX_HEADROOM_MULTIPLIER;
    return Math.clamp(withHeadroom, MIN_VECTOR_INDEX_CAPACITY, Integer.MAX_VALUE);
  }

  private List<MemorySource> memoryChunkSources() {
    return cypher.read(
        Cypher.CYPHER_LIST_MEMORY_CHUNK_SOURCES,
        Map.of(),
        result -> {
          List<MemorySource> sources = new ArrayList<>();
          while (result.hasNext()) {
            var row = result.next();
            sources.add(
                new MemorySource(
                    stringValue(row.get("existingChunkId")),
                    stringValue(row.get(Const.Params.SOURCE_LABEL)),
                    stringValue(row.get(Const.Params.SOURCE_ID)),
                    stringValue(row.get("title")),
                    stringValue(row.get("topic")),
                    stringValue(row.get("status")),
                    stringValue(row.get("severity")),
                    stringValue(row.get(Const.Params.TYPE)),
                    stringValue(row.get("priority")),
                    stringValue(row.get("source")),
                    stringValue(row.get("number")),
                    stringValue(row.get("rationale")),
                    stringValue(row.get("consequences")),
                    stringValue(row.get("content")),
                    stringValue(row.get("description")),
                    stringValue(row.get("summary")),
                    stringValue(row.get("evidence")),
                    stringValue(row.get("mitigation")),
                    stringValue(row.get("answer")),
                    stringValue(row.get("notes")),
                    stringValue(row.get("context")),
                    stringValue(row.get("decision")),
                    row.get("codeRefs").asList(GraphWriter::stringValue).stream()
                        .filter(ref -> !ref.isBlank())
                        .toList()));
          }
          return List.copyOf(sources);
        });
  }

  private static String stringValue(Value value) {
    return value == null || value.isNull() ? Const.Symbols.EMPTY : value.asObject().toString();
  }
}
