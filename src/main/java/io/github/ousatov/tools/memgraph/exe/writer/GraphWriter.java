package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.def.Const.Rag;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.exe.rag.MemoryChunkBuilder;
import io.github.ousatov.tools.memgraph.exe.rag.MemoryChunkBuilder.MemorySource;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.AnnotationWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.CallWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.CodeChunkWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.MemoryChunkWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.PendingCallWrite;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
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
  private static final Pattern CYPHER_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private final CypherExecutor cypher;
  private final GraphNodeWriter nodes;
  private final MemoryChunkBuilder memoryChunks = new MemoryChunkBuilder();
  private final CommonGraphWriter.Dependencies dependencies;
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
    CallEdgeWriter callEdges = new CallEdgeWriter(cypher, nodes);
    this.dependencies = new CommonGraphWriter.Dependencies(cypher, callEdges, nodes);
    this.stats = stats;
  }

  public CommonGraphWriter.Dependencies dependencies() {
    return dependencies;
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
          deleteCodeChunksForFile(file);
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
                    Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILES,
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

  /**
   * Removes Python definitions for {@code file} that were written under older module FQN schemes.
   */
  public void deleteStalePythonDefinitionsForFile(Path file, String currentModuleFqn) {
    Map<String, Object> params =
        Map.of(
            Params.PATH,
            file.toString(),
            Params.FQN,
            currentModuleFqn,
            Params.MODULE_PREFIX,
            currentModuleFqn + ".");
    cypher.run(Cypher.CYPHER_DELETE_STALE_PYTHON_MEMBERS_FOR_FILE, params);
    cypher.run(Cypher.CYPHER_DELETE_STALE_PYTHON_OWNERS_FOR_FILE, params);
    cypher.run(Cypher.CYPHER_DELETE_EMPTY_PYTHON_PACKAGES, Map.of());
  }

  /** Refreshes {@code :CodeRef} resolution edges to the current project-scoped code graph. */
  public void resolveCodeRefs() {
    List.of(
            Cypher.CYPHER_CLEAR_CODE_PACKAGE_CODE_REF_RESOLUTIONS,
            Cypher.CYPHER_RESOLVE_JAVA_CODE_REFS_CODE,
            Cypher.CYPHER_RESOLVE_JAVA_CODE_REFS_PACKAGE,
            Cypher.CYPHER_RESOLVE_JAVASCRIPT_CODE_REFS_CODE,
            Cypher.CYPHER_RESOLVE_JAVASCRIPT_CODE_REFS_PACKAGE,
            Cypher.CYPHER_RESOLVE_PYTHON_CODE_REFS_CODE,
            Cypher.CYPHER_RESOLVE_PYTHON_CODE_REFS_PACKAGE,
            Cypher.CYPHER_RESOLVE_DYNAMIC_CODE_REFS_CODE,
            Cypher.CYPHER_RESOLVE_DYNAMIC_CODE_REFS_PACKAGE,
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
          Map.of(Params.PATHS, paths, Params.LANGUAGE, language.graphName()),
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
    List<String> paths = files.stream().map(Path::toString).toList();
    return cypher.read(
        Cypher.CYPHER_GET_FILE_PATHS_MISSING_CODE_CHUNKS,
        Map.of(Params.PATHS, paths),
        result -> {
          Set<Path> missing = new HashSet<>();
          while (result.hasNext()) {
            String path = result.next().get(Params.PATH).asString(null);
            if (path != null) {
              missing.add(Path.of(path));
            }
          }
          return missing;
        });
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

  /** Returns graph languages with file paths under the active source root. */
  public Set<SourceLanguage> getLanguagesInSourceRoot(Path sourceRoot) {
    String sourceRootText = sourceRoot.toString();
    String separator = sourceRoot.getFileSystem().getSeparator();
    String sourceRootPrefix =
        sourceRootText.endsWith(separator) ? sourceRootText : sourceRootText + separator;
    return cypher.read(
        Cypher.CYPHER_GET_LANGUAGES_IN_SOURCE_ROOT,
        Map.of(Params.SOURCE_ROOT, sourceRootText, Params.SOURCE_ROOT_PREFIX, sourceRootPrefix),
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
        Map.of(Params.PATH, file.toString(), Params.LANGUAGE, language.graphName()),
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
    if (SourceLanguage.JAVA.equals(language)) {
      return Cypher.CYPHER_GET_JAVA_FILES_LAST_MODIFIED;
    }
    if (SourceLanguage.JAVASCRIPT.equals(language)) {
      return Cypher.CYPHER_GET_JAVASCRIPT_FILES_LAST_MODIFIED;
    }
    if (SourceLanguage.PYTHON.equals(language)) {
      return Cypher.CYPHER_GET_PYTHON_FILES_LAST_MODIFIED;
    }
    return Cypher.CYPHER_GET_CTAGS_FILES_LAST_MODIFIED;
  }

  private static String getFilesInSourceRootCypher(SourceLanguage language) {
    if (SourceLanguage.JAVA.equals(language)) {
      return Cypher.CYPHER_GET_JAVA_FILES_IN_SOURCE_ROOT;
    }
    if (SourceLanguage.JAVASCRIPT.equals(language)) {
      return Cypher.CYPHER_GET_JAVASCRIPT_FILES_IN_SOURCE_ROOT;
    }
    if (SourceLanguage.PYTHON.equals(language)) {
      return Cypher.CYPHER_GET_PYTHON_FILES_IN_SOURCE_ROOT;
    }
    return Cypher.CYPHER_GET_CTAGS_FILES_IN_SOURCE_ROOT;
  }

  private static String getSourceRootHintCypher(SourceLanguage language) {
    if (SourceLanguage.JAVA.equals(language)) {
      return Cypher.CYPHER_GET_JAVA_SOURCE_ROOT_HINT_FOR_FILE;
    }
    if (SourceLanguage.JAVASCRIPT.equals(language)) {
      return Cypher.CYPHER_GET_JAVASCRIPT_SOURCE_ROOT_HINT_FOR_FILE;
    }
    if (SourceLanguage.PYTHON.equals(language)) {
      return Cypher.CYPHER_GET_PYTHON_SOURCE_ROOT_HINT_FOR_FILE;
    }
    return Cypher.CYPHER_GET_CTAGS_SOURCE_ROOT_HINT_FOR_FILE;
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
              Params.SOURCE_ROOT,
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
    cypher.run(Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILE, Map.of(Params.PATH, file.toString()));
  }

  /** Upserts derived {@code :MemoryChunk} rows for current Memory records. */
  public long upsertMemoryChunks() {
    List<MemoryChunkWrite> chunks = memoryChunkSources().stream().map(memoryChunks::build).toList();
    deleteStaleMemoryChunks(chunks);
    cypher.runBatch(
        Cypher.CYPHER_UPSERT_MEMORY_CHUNKS_BATCH,
        chunks.stream().map(MemoryChunkWrite::params).toList());
    return chunks.size();
  }

  private void deleteStaleMemoryChunks(Collection<MemoryChunkWrite> chunks) {
    List<Map<String, Object>> rows =
        chunks.stream()
            .map(
                chunk ->
                    Map.<String, Object>of(
                        Params.SOURCE_LABEL,
                        chunk.sourceLabel(),
                        Params.SOURCE_ID,
                        chunk.sourceId()))
            .toList();
    cypher.run(Cypher.CYPHER_DELETE_STALE_MEMORY_CHUNKS, Map.of("rows", rows));
  }

  /** Refreshes stale {@code :CodeChunk.embedding} values with Memgraph's embeddings module. */
  public void refreshCodeChunkEmbeddings(EmbeddingSettings settings) {
    if (!settings.enabled()) {
      return;
    }
    validateCypherIdentifier(settings.indexName(), "code embedding index name");

    int dimension = embeddingDimension(settings);
    ensureCodeChunkVectorIndex(settings, dimension);
    long stale = countStaleCodeChunkEmbeddings(settings, dimension);
    long embedded = 0L;
    int batchSize = settings.batchSize();
    while (stale > 0) {
      EmbeddingBatchResult batch = refreshCodeChunkEmbeddingBatch(settings, dimension, batchSize);
      if (!batch.success()) {
        if (batchSize == 1) {
          throw codeChunkEmbeddingFailure(batch.ids());
        }
        int nextBatchSize = Math.max(1, batchSize / 2);
        log.warn(
            "Memgraph embeddings.node_sentence returned false for {} CodeChunk(s); retrying with"
                + " batch size {}.",
            batch.ids().size(),
            nextBatchSize);
        batchSize = nextBatchSize;
        continue;
      }
      long batchCount = batch.ids().size();
      if (batchCount == 0) {
        throw new ProcessingException("Memgraph embeddings refresh made no progress");
      }
      updateCodeChunkEmbeddingMetadata(settings, dimension, batch.ids());
      embedded += batchCount;
      stale -= batchCount;
    }
    log.debug(
        "Refreshed {} CodeChunk embedding(s) using model '{}' ({} dimensions).",
        embedded,
        settings.modelName(),
        dimension);
  }

  /** Refreshes stale {@code :MemoryChunk.embedding} values with Memgraph's embeddings module. */
  public void refreshMemoryChunkEmbeddings(EmbeddingSettings settings) {
    if (!settings.enabled()) {
      return;
    }
    validateCypherIdentifier(settings.indexName(), "memory embedding index name");

    int dimension = embeddingDimension(settings);
    ensureMemoryChunkVectorIndex(settings, dimension);
    long stale = countStaleMemoryChunkEmbeddings(settings, dimension);
    long embedded = 0L;
    int batchSize = settings.batchSize();
    while (stale > 0) {
      EmbeddingBatchResult batch = refreshMemoryChunkEmbeddingBatch(settings, dimension, batchSize);
      if (!batch.success()) {
        if (batchSize == 1) {
          throw memoryChunkEmbeddingFailure(batch.ids());
        }
        int nextBatchSize = Math.max(1, batchSize / 2);
        log.warn(
            "Memgraph embeddings.node_sentence returned false for {} MemoryChunk(s); retrying with"
                + " batch size {}.",
            batch.ids().size(),
            nextBatchSize);
        batchSize = nextBatchSize;
        continue;
      }
      long batchCount = batch.ids().size();
      if (batchCount == 0) {
        throw new ProcessingException("Memgraph memory embeddings refresh made no progress");
      }
      updateMemoryChunkEmbeddingMetadata(settings, dimension, batch.ids());
      embedded += batchCount;
      stale -= batchCount;
    }
    log.debug(
        "Refreshed {} MemoryChunk embedding(s) using model '{}' ({} dimensions).",
        embedded,
        settings.modelName(),
        dimension);
  }

  private int embeddingDimension(EmbeddingSettings settings) {
    Map<String, Object> params = Map.of("config", settings.modelConfiguration());
    return cypher.read(
        Cypher.CYPHER_CODE_EMBEDDING_MODEL_INFO,
        params,
        result -> {
          if (!result.hasNext()) {
            throw new ProcessingException("Memgraph embeddings.model_info returned no rows");
          }
          int dimension = result.next().get("info").get(Rag.DIMENSION).asInt(0);
          if (dimension < 1) {
            throw new ProcessingException(
                "Memgraph embeddings.model_info returned invalid dimension " + dimension);
          }
          return dimension;
        });
  }

  private void ensureCodeChunkVectorIndex(EmbeddingSettings settings, int dimension) {
    Optional<VectorIndexInfo> existing = vectorIndexInfo(settings.indexName());
    if (existing.isPresent()) {
      verifyCodeChunkVectorIndex(settings, dimension, existing.get());
      return;
    }

    long chunkCount = countCodeChunks();
    int capacity =
        settings.capacity() > 0
            ? settings.capacity()
            : Math.max(1, Math.toIntExact(Math.min(Integer.MAX_VALUE, chunkCount)));
    Map<String, Object> config =
        Map.of(
            Rag.DIMENSION,
            dimension,
            Rag.CAPACITY,
            capacity,
            Rag.METRIC,
            settings.metric(),
            Rag.SCALAR_KIND,
            settings.scalarKind());
    cypher.run(createCodeChunkVectorIndexCypher(settings), Map.of("config", config));
  }

  private void ensureMemoryChunkVectorIndex(EmbeddingSettings settings, int dimension) {
    Optional<VectorIndexInfo> existing = vectorIndexInfo(settings.indexName());
    if (existing.isPresent()) {
      verifyMemoryChunkVectorIndex(settings, dimension, existing.get());
      return;
    }

    long chunkCount = countMemoryChunks();
    int capacity =
        settings.capacity() > 0
            ? settings.capacity()
            : Math.max(1, Math.toIntExact(Math.min(Integer.MAX_VALUE, chunkCount)));
    Map<String, Object> config =
        Map.of(
            Rag.DIMENSION,
            dimension,
            Rag.CAPACITY,
            capacity,
            Rag.METRIC,
            settings.metric(),
            Rag.SCALAR_KIND,
            settings.scalarKind());
    cypher.run(createMemoryChunkVectorIndexCypher(settings), Map.of("config", config));
  }

  private Optional<VectorIndexInfo> vectorIndexInfo(String indexName) {
    return cypher.read(
        Cypher.CYPHER_SHOW_VECTOR_INDEX_INFO,
        Map.of(),
        result -> {
          while (result.hasNext()) {
            var row = result.next();
            if (indexName.equals(row.get("index_name").asString(""))) {
              return Optional.of(
                  new VectorIndexInfo(
                      row.get("label").asString(""),
                      row.get("property").asString(""),
                      row.get(Rag.DIMENSION).asInt(0),
                      row.get(Rag.METRIC).asString(""),
                      row.get(Rag.SCALAR_KIND).asString("")));
            }
          }
          return Optional.empty();
        });
  }

  private static void verifyCodeChunkVectorIndex(
      EmbeddingSettings settings, int dimension, VectorIndexInfo index) {
    if (!"CodeChunk".equals(index.label())
        || !EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY.equals(index.property())
        || dimension != index.dimension()
        || !settings.metric().equals(index.metric())
        || (!index.scalarKind().isBlank() && !settings.scalarKind().equals(index.scalarKind()))) {
      throw new ProcessingException(
          "Vector index '"
              + settings.indexName()
              + "' exists but is not compatible with requested CodeChunk embeddings");
    }
  }

  private static void verifyMemoryChunkVectorIndex(
      EmbeddingSettings settings, int dimension, VectorIndexInfo index) {
    if (!"MemoryChunk".equals(index.label())
        || !EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY.equals(index.property())
        || dimension != index.dimension()
        || !settings.metric().equals(index.metric())
        || (!index.scalarKind().isBlank() && !settings.scalarKind().equals(index.scalarKind()))) {
      throw new ProcessingException(
          "Vector index '"
              + settings.indexName()
              + "' exists but is not compatible with requested MemoryChunk embeddings");
    }
  }

  private long countCodeChunks() {
    return cypher.read(
        Cypher.CYPHER_COUNT_CODE_CHUNKS, Map.of(), result -> result.single().get("count").asLong());
  }

  private long countMemoryChunks() {
    return cypher.read(
        Cypher.CYPHER_COUNT_MEMORY_CHUNKS,
        Map.of(),
        result -> result.single().get("count").asLong());
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
                    stringValue(row.get("sourceLabel")),
                    stringValue(row.get("sourceId")),
                    stringValue(row.get("title")),
                    stringValue(row.get("topic")),
                    stringValue(row.get("status")),
                    stringValue(row.get("severity")),
                    stringValue(row.get("type")),
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
    return value == null || value.isNull() ? "" : value.asObject().toString();
  }

  private long countStaleCodeChunkEmbeddings(EmbeddingSettings settings, int dimension) {
    Map<String, Object> params =
        Map.of("modelName", settings.modelName(), Rag.DIMENSION, dimension);
    return cypher.read(
        Cypher.CYPHER_COUNT_STALE_CODE_CHUNK_EMBEDDINGS,
        params,
        result -> result.single().get("count").asLong());
  }

  private long countStaleMemoryChunkEmbeddings(EmbeddingSettings settings, int dimension) {
    Map<String, Object> params =
        Map.of("modelName", settings.modelName(), Rag.DIMENSION, dimension);
    return cypher.read(
        Cypher.CYPHER_COUNT_STALE_MEMORY_CHUNK_EMBEDDINGS,
        params,
        result -> result.single().get("count").asLong());
  }

  private EmbeddingBatchResult refreshCodeChunkEmbeddingBatch(
      EmbeddingSettings settings, int dimension, int batchSize) {
    Map<String, Object> config = codeEmbeddingConfig(settings, batchSize);
    Map<String, Object> params =
        Map.of(
            "modelName",
            settings.modelName(),
            Rag.DIMENSION,
            dimension,
            "limit",
            batchSize,
            "config",
            config);
    return cypher.read(
        Cypher.CYPHER_REFRESH_CODE_CHUNK_EMBEDDING_BATCH,
        params,
        result -> getEmbeddingBatchResult(dimension, result));
  }

  private @NonNull EmbeddingBatchResult getEmbeddingBatchResult(int dimension, Result result) {
    if (!result.hasNext()) {
      return new EmbeddingBatchResult(true, List.of());
    }
    var row = result.single();
    boolean success = row.get("success").asBoolean(false);
    int actualDimension = row.get(Rag.DIMENSION).asInt(0);
    if (success && actualDimension != dimension) {
      throw new ProcessingException(
          "Memgraph embeddings.node_sentence returned dimension "
              + actualDimension
              + " but expected "
              + dimension);
    }
    return new EmbeddingBatchResult(success, row.get("ids").asList(Value::asString));
  }

  private EmbeddingBatchResult refreshMemoryChunkEmbeddingBatch(
      EmbeddingSettings settings, int dimension, int batchSize) {
    Map<String, Object> config = memoryEmbeddingConfig(settings, batchSize);
    Map<String, Object> params =
        Map.of(
            "modelName",
            settings.modelName(),
            Rag.DIMENSION,
            dimension,
            "limit",
            batchSize,
            "config",
            config);
    return cypher.read(
        Cypher.CYPHER_REFRESH_MEMORY_CHUNK_EMBEDDING_BATCH,
        params,
        result -> getEmbeddingBatchResult(dimension, result));
  }

  private static Map<String, Object> codeEmbeddingConfig(
      EmbeddingSettings settings, int batchSize) {
    Map<String, Object> config = new HashMap<>(settings.codeNodeSentenceConfiguration());
    config.put(Rag.BATCH_SIZE, batchSize);
    config.put(Rag.CHUNK_SIZE, Math.min(settings.chunkSize(), batchSize));
    return config;
  }

  private static Map<String, Object> memoryEmbeddingConfig(
      EmbeddingSettings settings, int batchSize) {
    Map<String, Object> config = new HashMap<>(settings.memoryNodeSentenceConfiguration());
    config.put(Rag.BATCH_SIZE, batchSize);
    config.put(Rag.CHUNK_SIZE, Math.min(settings.chunkSize(), batchSize));
    return config;
  }

  private void updateCodeChunkEmbeddingMetadata(
      EmbeddingSettings settings, int dimension, List<String> embeddedIds) {
    if (embeddedIds.isEmpty()) {
      return;
    }
    cypher.run(
        Cypher.CYPHER_UPDATE_CODE_CHUNK_EMBEDDING_METADATA,
        Map.of("ids", embeddedIds, "modelName", settings.modelName(), Rag.DIMENSION, dimension));
  }

  private void updateMemoryChunkEmbeddingMetadata(
      EmbeddingSettings settings, int dimension, List<String> embeddedIds) {
    if (embeddedIds.isEmpty()) {
      return;
    }
    cypher.run(
        Cypher.CYPHER_UPDATE_MEMORY_CHUNK_EMBEDDING_METADATA,
        Map.of("ids", embeddedIds, "modelName", settings.modelName(), Rag.DIMENSION, dimension));
  }

  private ProcessingException codeChunkEmbeddingFailure(List<String> ids) {
    if (ids.isEmpty()) {
      return new ProcessingException(
          "Memgraph embeddings.node_sentence returned false and no CodeChunk id was returned");
    }
    String id = ids.get(0);
    String detail =
        cypher.read(
            Cypher.CYPHER_GET_CODE_CHUNK_EMBEDDING_FAILURE_DETAIL,
            Map.of("id", id),
            result -> {
              if (!result.hasNext()) {
                return "id=" + id;
              }
              var row = result.single();
              return "id="
                  + id
                  + ", path="
                  + row.get("path").asString("")
                  + ", source="
                  + row.get("sourceLabel").asString("")
                  + " "
                  + row.get("sourceId").asString("")
                  + ", preview="
                  + row.get("preview").asString("").replace('\n', ' ');
            });
    return new ProcessingException(
        "Memgraph embeddings.node_sentence returned false for single CodeChunk " + detail);
  }

  private ProcessingException memoryChunkEmbeddingFailure(List<String> ids) {
    if (ids.isEmpty()) {
      return new ProcessingException(
          "Memgraph embeddings.node_sentence returned false and no MemoryChunk id was returned");
    }
    String id = ids.get(0);
    String detail =
        cypher.read(
            Cypher.CYPHER_GET_MEMORY_CHUNK_EMBEDDING_FAILURE_DETAIL,
            Map.of("id", id),
            result -> {
              if (!result.hasNext()) {
                return "id=" + id;
              }
              var row = result.single();
              return "id="
                  + id
                  + ", source="
                  + row.get("sourceLabel").asString("")
                  + " "
                  + row.get("sourceId").asString("")
                  + ", preview="
                  + row.get("preview").asString("").replace('\n', ' ');
            });
    return new ProcessingException(
        "Memgraph embeddings.node_sentence returned false for single MemoryChunk " + detail);
  }

  private static String createCodeChunkVectorIndexCypher(EmbeddingSettings settings) {
    validateCypherIdentifier(settings.indexName(), "code embedding index name");
    validateCypherIdentifier(
        EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY, "code embedding property name");
    return Cypher.CYPHER_CREATE_CODE_CHUNK_VECTOR_INDEX
        .replace("__INDEX_NAME__", settings.indexName())
        .replace("__EMBEDDING_PROPERTY__", EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY);
  }

  private static String createMemoryChunkVectorIndexCypher(EmbeddingSettings settings) {
    validateCypherIdentifier(settings.indexName(), "memory embedding index name");
    validateCypherIdentifier(
        EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY, "memory embedding property name");
    return Cypher.CYPHER_CREATE_MEMORY_CHUNK_VECTOR_INDEX
        .replace("__INDEX_NAME__", settings.indexName())
        .replace("__EMBEDDING_PROPERTY__", EmbeddingSettings.DEFAULT_EMBEDDING_PROPERTY);
  }

  private static void validateCypherIdentifier(String value, String name) {
    if (!CYPHER_IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a Cypher identifier");
    }
  }

  private record VectorIndexInfo(
      String label, String property, int dimension, String metric, String scalarKind) {}

  private record EmbeddingBatchResult(boolean success, List<String> ids) {}
}
