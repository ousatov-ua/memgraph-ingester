package io.github.ousatov.tools.memgraph.exe.ingestion;

import io.github.ousatov.tools.memgraph.IngesterCli;
import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.adapter.JavaLanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.analyze.ParseService;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionMetricsCollector;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleOutput;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleProgress;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.schema.Memgraph;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import io.github.ousatov.tools.memgraph.vo.Settings;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.vo.ingestion.PreparedFailure;
import io.github.ousatov.tools.memgraph.vo.ingestion.PreparedFile;
import io.github.ousatov.tools.memgraph.vo.ingestion.PreparedSkip;
import io.github.ousatov.tools.memgraph.vo.ingestion.PreparedWrite;
import io.github.ousatov.tools.memgraph.vo.ingestion.SourceFile;
import io.github.ousatov.tools.memgraph.vo.ingestion.StoredFileState;
import io.github.ousatov.tools.memgraph.vo.writer.EmbeddingRefreshResult;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks the source tree and dispatches files to matching {@link LanguageAdapter} instances and
 * {@link GraphWriter}.
 *
 * <p>Tracks parse and ingest failures independently and returns the total count so {@link
 * IngesterCli} can return a non-zero exit code when any file fails.
 *
 * @author Oleksii Usatov
 */
public final class IngestionOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(IngestionOrchestrator.class);

  private static final Duration SHUTDOWN_TIMEOUT =
      AppConfig.durationValue("ingestion.shutdown-timeout");
  private static final int FILE_TX_RETRY_ATTEMPTS =
      AppConfig.intValue("ingestion.file-transaction-retry.max-attempts");
  private static final long FILE_TX_INITIAL_BACKOFF_MS =
      AppConfig.durationValue("ingestion.file-transaction-retry.initial-backoff").toMillis();
  private static final long FILE_TX_MAX_BACKOFF_MS =
      AppConfig.durationValue("ingestion.file-transaction-retry.max-backoff").toMillis();
  private final Path sourceRoot;
  private final String project;
  private final int threads;
  private final Driver driver;
  private final List<LanguageAdapter<?>> languageAdapters;
  private final WatchSession watchSession = new WatchSession(this);
  private boolean incremental;
  private String analysisCacheKey = "";
  private EmbeddingSettings codeEmbeddings = EmbeddingSettings.disabled();
  private EmbeddingSettings memoryEmbeddings = EmbeddingSettings.disabled();

  /**
   * @param sourceRoot root directory to walk for {@code .java} files
   * @param project project name used to scope all graph writes
   * @param threads number of parallel worker threads (1 = sequential)
   * @param driver shared Bolt driver — not closed by this orchestrator
   * @param parseService per-thread parser service
   */
  public IngestionOrchestrator(
      Path sourceRoot, String project, int threads, Driver driver, ParseService parseService) {
    this(sourceRoot, project, threads, driver, List.of(new JavaLanguageAdapter(parseService)));
  }

  /**
   * @param sourceRoot root directory to walk
   * @param project project name used to scope all graph writes
   * @param threads number of parallel worker threads (1 = sequential)
   * @param driver shared Bolt driver — not closed by this orchestrator
   * @param languageAdapter parser and graph writer adapter for one language
   */
  public IngestionOrchestrator(
      Path sourceRoot,
      String project,
      int threads,
      Driver driver,
      LanguageAdapter<?> languageAdapter) {
    this(sourceRoot, project, threads, driver, List.of(languageAdapter));
  }

  /**
   * @param sourceRoot root directory to walk
   * @param project project name used to scope all graph writes
   * @param threads number of parallel worker threads (1 = sequential)
   * @param driver shared Bolt driver — not closed by this orchestrator
   * @param languageAdapters parser and graph writer adapters to select from by file extension
   */
  public IngestionOrchestrator(
      Path sourceRoot,
      String project,
      int threads,
      Driver driver,
      List<LanguageAdapter<?>> languageAdapters) {
    if (languageAdapters.isEmpty()) {
      throw new IllegalArgumentException("At least one language adapter is required");
    }
    this.sourceRoot = sourceRoot;
    this.project = project;
    this.threads = threads;
    this.driver = driver;
    this.languageAdapters = List.copyOf(languageAdapters);
  }

  /**
   * Runs the full ingestion and returns the number of files that failed.
   *
   * @param settings if true, deletes all data before ingesting
   * @return number of failed files; 0 means complete success
   */
  public int run(Settings settings) {
    log.debug("Proceeding with ingestion, settings: {}", settings);
    this.codeEmbeddings = settings.codeEmbeddings();
    this.memoryEmbeddings = settings.memoryEmbeddings();
    this.analysisCacheKey = settings.analysisCacheKey();
    IngestionRunStats stats = new IngestionRunStats(threads);
    this.incremental = settings.incremental();

    List<SourceFile> files = discoverSourceFiles();
    runBootstrap(settings, stats, sourceLanguages(files));
    stats.setTotalFiles(files.size());
    List<Path> retainedSourcePaths = retainedSourcePaths(files, stats);
    String discoveryMessage =
        "Found "
            + files.size()
            + " supported source files across "
            + languageAdapters.size()
            + " adapter(s).";
    log.info(discoveryMessage);
    ConsoleOutput.status(discoveryMessage);

    StoredFileState storedFiles = preloadStoredFileState(files);
    if (incremental) {
      log.info(
          "Pre-loaded {} stored file timestamps for incremental mode.",
          storedFiles.lastModifiedByPath().size());
    }
    Map<LanguageAdapter<?>, RuntimeException> failedAdapterPreparations =
        prepareAdapters(files, storedFiles);

    int failures;
    try (IngestionProgress progress = IngestionProgress.start(files.size())) {
      if (threads == 1) {
        failures =
            ingestSequential(
                files,
                storedFiles,
                failedAdapterPreparations,
                retainedSourcePaths,
                stats,
                progress);
      } else {
        failures =
            ingestParallel(
                files,
                storedFiles,
                failedAdapterPreparations,
                retainedSourcePaths,
                stats,
                progress);
      }
    }

    if (failures == 0) {
      deleteMissingSourceFiles(files, retainedSourcePaths, stats);
    } else {
      log.warn(
          "Skipping missing-file cleanup because {} file(s) failed to ingest; existing graph"
              + " state will be kept for retry.",
          failures);
    }

    try {
      runPostProcessing(stats);
    } catch (RuntimeException e) {
      if (settings.watch() && WatchSession.isInterruptedFailure(e)) {
        Thread.currentThread().interrupt();
        return failures;
      }
      throw e;
    }

    if (settings.watch()) {
      watchSession.start();
    }
    return failures;
  }

  private Map<LanguageAdapter<?>, RuntimeException> prepareAdapters(
      List<SourceFile> files, StoredFileState storedFiles) {
    Set<LanguageAdapter<?>> prepared = new LinkedHashSet<>();
    Map<LanguageAdapter<?>, RuntimeException> failed = new LinkedHashMap<>();
    for (SourceFile file : files) {
      if (isFileUnchanged(file.path(), storedFiles)) {
        continue;
      }
      LanguageAdapter<?> adapter = file.adapter();
      if (prepared.add(adapter) && !failed.containsKey(adapter)) {
        try {
          adapter.prepare();
        } catch (RuntimeException e) {
          failed.put(adapter, e);
          log.warn("Failed to prepare {} adapter: {}", adapter.displayName(), e.getMessage());
        }
      }
    }
    return Map.copyOf(failed);
  }

  /** Test-friendly entry point: re-ingests the given files using the watch-mode pipeline. */
  public void ingestChangedFiles(Set<Path> files) {
    watchSession.ingestChangedFiles(files);
  }

  Path sourceRoot() {
    return sourceRoot;
  }

  String project() {
    return project;
  }

  String analysisCacheKey() {
    return analysisCacheKey;
  }

  int threads() {
    return threads;
  }

  Driver driver() {
    return driver;
  }

  private void runBootstrap(
      Settings settings, IngestionRunStats stats, List<SourceLanguage> sourceLanguages) {
    try (Session bootstrap = driver.session()) {
      GraphWriter bootstrapWriter = new GraphWriter(bootstrap, project, stats);
      if (settings.wipeAllData()) {
        Memgraph.wipeAllData(bootstrap);
        log.info("Wiped all data from Memgraph");
      }
      if (settings.applySchema()) {
        String applyingSchema = "Applying schema to Memgraph ...";
        log.info(applyingSchema);
        ConsoleOutput.status(applyingSchema);
        Memgraph.applySchema(bootstrap);
        String appliedSchema = "Applied schema to Memgraph";
        log.info(appliedSchema);
        ConsoleOutput.status(appliedSchema);
      } else if (Memgraph.needsSchemaUpdate(bootstrap)) {
        Memgraph.applySchema(bootstrap);
        log.info("Applied schema migrations to Memgraph");
      }
      if (settings.wipeProjectCode()) {
        String wipeProjectCode = "Wiping existing code graph for project '" + project + "'...";
        log.info(wipeProjectCode);
        ConsoleOutput.status(wipeProjectCode);
        bootstrapWriter.wipe();
      }
      if (settings.wipeProjectMemories()) {
        log.info("Wiping existing memory graph for project '{}'...", project);
        bootstrapWriter.wipeMemories();
      }
      if (settings.wipeCodeRag()) {
        log.info("Wiping existing CodeChunk RAG rows for project '{}'...", project);
        bootstrapWriter.wipeCodeRag();
      }
      if (settings.wipeMemoryRag()) {
        log.info("Wiping existing MemoryChunk RAG rows for project '{}'...", project);
        bootstrapWriter.wipeMemoryRag();
      }
      bootstrapWriter.upsertProject(sourceRoot, sourceLanguages);
      log.debug(
          "Upserted :Project -> :Language -> :Code and :Project -> :Memory anchors for '{}'",
          project);
      bootstrapWriter.backfillMethodOwnerMetadata();
      log.debug("Backfilled :Method owner metadata for '{}'", project);
    }
  }

  private void runPostProcessing(IngestionRunStats stats) {
    try (Session session = driver.session()) {
      GraphWriter postWriter = new GraphWriter(session, project, stats);
      refreshDerivedGraphArtifacts(postWriter);
      refreshChunkEmbeddings(postWriter, false);
      ConsoleOutput.finishStatus();
      printMetrics(session);
      printPerformance(stats);
    }
  }

  void refreshRetainedFilesAfterDelete(
      GraphWriter writer,
      Collection<Path> paths,
      Map<Path, SourceFile> currentFilesByPath,
      IngestionRunStats stats) {
    for (Path path : paths.stream().sorted().toList()) {
      try {
        Optional<SourceFile> retainedFile = sourceFileFor(path, currentFilesByPath, writer);
        if (retainedFile.isEmpty()) {
          continue;
        }
        if (!ingestFileBatched(writer, retainedFile.get(), StoredFileState.empty(), stats)) {
          WatchSession.logWatchWarning("Failed to refresh retained file after delete: {}", path);
        }
      } catch (RuntimeException e) {
        WatchSession.logWatchWarning(
            "Failed to refresh retained file after delete on {}: {}", path, e.getMessage());
      }
    }
  }

  boolean deleteSourceFileWithRetry(Path file, Consumer<Path> deleteAction) {
    long backoffMs = FILE_TX_INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= FILE_TX_RETRY_ATTEMPTS; attempt++) {
      try {
        deleteAction.accept(file);
        return true;
      } catch (RuntimeException e) {
        if (!GraphWriter.isRetryable(e) || attempt == FILE_TX_RETRY_ATTEMPTS) {
          WatchSession.logWatchWarning(
              "Failed to update graph for watch delete on {}: {}", file, e.getMessage());
          return false;
        }
        backoffMs = sleepBeforeFileRetry(file, attempt, e, backoffMs);
      }
    }
    return false;
  }

  Optional<Set<Path>> retainedFilesSharingDefinitionsWithRetry(
      Path file, Function<Path, Set<Path>> retainedFilesLookup) {
    long backoffMs = FILE_TX_INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= FILE_TX_RETRY_ATTEMPTS; attempt++) {
      try {
        return Optional.of(retainedFilesLookup.apply(file));
      } catch (RuntimeException e) {
        if (!GraphWriter.isRetryable(e) || attempt == FILE_TX_RETRY_ATTEMPTS) {
          WatchSession.logWatchWarning(
              "Failed to read retained files sharing definitions with {}: {}",
              file,
              e.getMessage());
          return Optional.empty();
        }
        backoffMs = sleepBeforeFileRetry(file, attempt, e, backoffMs);
      }
    }
    return Optional.empty();
  }

  /** Refreshes graph artifacts that depend on all available code nodes being present. */
  void refreshDerivedGraphArtifacts(GraphWriter writer) {
    writer.resolvePendingCallsForChangedDefinitions();
    log.debug("Resolved changed pending owner/name CALLS edges for '{}'", project);
    writer.deletePhantomMethods();
    log.debug("Removed phantom external Method nodes for '{}'", project);
    writer.deleteEmptyPackages();
    log.debug("Removed empty Package nodes for '{}'", project);
    writer.resolveCodeRefs();
    log.debug("Refreshed :CodeRef resolution edges for '{}'", project);
  }

  void refreshChunkEmbeddings(GraphWriter writer, boolean watchMode) {
    if (codeEmbeddings.enabled()) {
      refreshEmbeddingType(
          writer,
          watchMode,
          codeEmbeddings,
          "CodeChunk",
          w -> w.refreshCodeChunkEmbeddings(codeEmbeddings, watchMode),
          "Ingestion completed; use --no-code-embeddings to suppress this warning or run a"
              + " Memgraph image with embeddings and vector-index support.");
    }
    if (!memoryEmbeddings.enabled()) {
      return;
    }
    long memoryChunkCount = writer.upsertMemoryChunks();
    log.debug("Upserted {} MemoryChunk row(s) for '{}'", memoryChunkCount, project);
    refreshEmbeddingType(
        writer,
        watchMode,
        memoryEmbeddings,
        "MemoryChunk",
        w -> w.refreshMemoryChunkEmbeddings(memoryEmbeddings),
        "MemoryChunk rows were synced; use --no-memory-embeddings to suppress this warning or run"
            + " a Memgraph image with embeddings and vector-index support.");
  }

  private void refreshEmbeddingType(
      GraphWriter writer,
      boolean watchMode,
      EmbeddingSettings settings,
      String chunkLabel,
      Function<GraphWriter, EmbeddingRefreshResult> refreshFn,
      String warnDetail) {
    String refreshingMessage =
        "Refreshing "
            + chunkLabel
            + " embeddings for project '"
            + project
            + "' with Memgraph model '"
            + settings.modelName()
            + "'...";
    logEmbeddingRefresh(watchMode, refreshingMessage);
    long startedNanos = System.nanoTime();
    String consoleMessage = "Refreshing " + chunkLabel;
    ConsoleProgress progress =
        watchMode || !ConsoleStatusLine.isInteractive()
            ? null
            : ConsoleProgress.indeterminate(consoleMessage);
    try {
      EmbeddingRefreshResult result = refreshFn.apply(writer);
      String refreshedMessage =
          "Refreshed "
              + result.embedded()
              + " "
              + chunkLabel
              + " embedding(s) using model '"
              + settings.modelName()
              + "' ("
              + result.dimension()
              + " dimensions).";
      logEmbeddingRefresh(watchMode, refreshedMessage);
      if (progress != null) {
        progress.discard();
      }
    } catch (RuntimeException e) {
      if (progress != null) {
        progress.discard();
      }
      if (settings.required()) {
        throw new ProcessingException(
            "Required "
                + chunkLabel
                + " embedding refresh failed for project '"
                + project
                + "': "
                + e.getMessage(),
            e);
      }
      log.warn(
          "Skipping {} embedding refresh for project '{}': {}. {}",
          chunkLabel,
          project,
          e.getMessage(),
          warnDetail);
    } finally {
      writer
          .stats()
          .recordPhaseNanos(IngestionRunStats.PHASE_EMBEDDING, System.nanoTime() - startedNanos);
    }
  }

  private void logEmbeddingRefresh(boolean watchMode, String message) {
    if (watchMode) {
      log.debug(message);
    } else {
      log.info(message);
    }
  }

  @SuppressWarnings({Const.Warnings.STANDARD_OUTPUT, Const.Warnings.BROAD_EXCEPTION})
  private void printMetrics(Session session) {
    try {
      printReport(IngestionMetricsCollector.collect(session, project).toMarkdownTable());
    } catch (RuntimeException | LinkageError e) {
      log.warn("Could not print ingestion metrics for '{}': {}", project, e.getMessage());
    }
  }

  @SuppressWarnings({Const.Warnings.STANDARD_OUTPUT, Const.Warnings.BROAD_EXCEPTION})
  private void printPerformance(IngestionRunStats stats) {
    try {
      logReport(stats.snapshot().toMarkdownTable());
    } catch (RuntimeException | LinkageError e) {
      log.warn("Could not print ingestion performance for '{}': {}", project, e.getMessage());
    }
  }

  @SuppressWarnings(Const.Warnings.STANDARD_OUTPUT)
  private static void printReport(String report) {
    System.out.print(report);
    logReport(report);
  }

  private static void logReport(String report) {
    log.atInfo().setMessage("\n{}").addArgument(report::stripTrailing).log();
  }

  boolean shouldVisitDirectory(Path dir) {
    return shouldVisitDirectory(dir, languageAdapters);
  }

  private boolean shouldVisitDirectory(Path dir, List<LanguageAdapter<?>> adapters) {
    return !adaptersForDirectory(dir, adapters).isEmpty();
  }

  private List<LanguageAdapter<?>> adaptersForDirectory(
      Path dir, List<LanguageAdapter<?>> adapters) {
    Path localDir = LanguageAdapter.localPath(sourceRoot, dir);
    return adapters.stream().filter(adapter -> adapter.shouldVisitDirectory(localDir)).toList();
  }

  List<SourceFile> discoverSourceFiles() {
    Map<Path, SourceFile> byPath = new LinkedHashMap<>();
    List<LanguageAdapter<?>> defaultDiscoveryAdapters = new ArrayList<>();
    for (LanguageAdapter<?> adapter : languageAdapters) {
      if (adapter.usesCustomFileDiscovery()) {
        adapter
            .discoverFiles(sourceRoot)
            .forEach(file -> byPath.putIfAbsent(file, new SourceFile(file, adapter)));
      } else {
        defaultDiscoveryAdapters.add(adapter);
      }
    }
    discoverSourceFiles(defaultDiscoveryAdapters, byPath);
    return byPath.values().stream().sorted(Comparator.comparing(SourceFile::path)).toList();
  }

  private void discoverSourceFiles(
      List<LanguageAdapter<?>> adapters, Map<Path, SourceFile> byPath) {
    if (adapters.isEmpty()) {
      return;
    }
    try {
      Files.walkFileTree(
          sourceRoot,
          new SimpleFileVisitor<>() {
            @Override
            public @NonNull FileVisitResult preVisitDirectory(
                @NonNull Path dir, @NonNull BasicFileAttributes attrs) {
              return shouldVisitDirectory(dir, adapters)
                  ? FileVisitResult.CONTINUE
                  : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public @NonNull FileVisitResult visitFile(
                @NonNull Path file, @NonNull BasicFileAttributes attrs) {
              if (attrs.isRegularFile()) {
                Path fileDirectory = file.getParent() == null ? sourceRoot : file.getParent();
                adapterFor(file, adaptersForDirectory(fileDirectory, adapters))
                    .ifPresent(adapter -> byPath.putIfAbsent(file, new SourceFile(file, adapter)));
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new ProcessingException("Cannot walk source root", e);
    }
  }

  private StoredFileState preloadStoredFileState(List<SourceFile> files) {
    if (!incremental) {
      return StoredFileState.empty();
    }
    try (Session session = driver.session()) {
      GraphWriter writer =
          new GraphWriter(session, project, new IngestionRunStats(0), analysisCacheKey);
      Map<String, Long> preloaded = new HashMap<>();
      Set<String> pathsMissingCodeChunks = new HashSet<>();
      Map<SourceLanguage, List<Path>> pathsByLanguage = new LinkedHashMap<>();
      for (SourceFile file : files) {
        pathsByLanguage
            .computeIfAbsent(file.language(), ignored -> new ArrayList<>())
            .add(file.path());
      }
      for (var entry : pathsByLanguage.entrySet()) {
        preloaded.putAll(writer.getAllFileLastModified(entry.getValue(), entry.getKey()));
        writer.getFilePathsMissingCodeChunks(entry.getValue()).stream()
            .map(Path::toString)
            .forEach(pathsMissingCodeChunks::add);
      }
      return new StoredFileState(Map.copyOf(preloaded), Set.copyOf(pathsMissingCodeChunks), true);
    } catch (RuntimeException e) {
      log.warn(
          "Could not batch-fetch stored source files; changed files will be ingested"
              + " transactionally: {}",
          e.getMessage());
      return StoredFileState.unreliable();
    }
  }

  private List<Path> retainedSourcePaths(List<SourceFile> files, IngestionRunStats stats) {
    try (Session session = driver.session()) {
      return retainedSourcePaths(files, new GraphWriter(session, project, stats));
    }
  }

  List<Path> retainedSourcePaths(List<SourceFile> files, GraphWriter writer) {
    Set<Path> retainedPaths = new LinkedHashSet<>();
    files.stream().map(SourceFile::path).forEach(retainedPaths::add);
    writer.getFilePathsInSourceRoot(sourceRoot).stream()
        .filter(path -> !retainedPaths.contains(path))
        .filter(Files::exists)
        .filter(this::shouldRetainExistingSourcePath)
        .sorted()
        .forEach(retainedPaths::add);
    writer.getRetainedFilePathsOutsideSourceRoot(sourceRoot).stream()
        .filter(Files::exists)
        .sorted()
        .forEach(retainedPaths::add);
    return List.copyOf(retainedPaths);
  }

  private boolean shouldRetainExistingSourcePath(Path path) {
    return adapterFor(path).isPresent() || adapterForDeletedPath(path).isEmpty();
  }

  private void deleteMissingSourceFiles(
      List<SourceFile> files, Collection<Path> retainedPaths, IngestionRunStats stats) {
    Map<SourceLanguage, List<Path>> pathsByLanguage = new LinkedHashMap<>();
    Map<Path, SourceFile> currentFilesByPath = new LinkedHashMap<>();
    for (SourceFile file : files) {
      currentFilesByPath.put(file.path(), file);
      pathsByLanguage
          .computeIfAbsent(file.language(), ignored -> new ArrayList<>())
          .add(file.path());
    }
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project, stats, analysisCacheKey);
      writer.setRetainedSourcePaths(retainedPaths);
      Set<Path> retainedPathSet = new HashSet<>(retainedPaths);
      Set<Path> refreshAfterDelete = new LinkedHashSet<>();
      for (SourceLanguage language : languagesForCleanup(writer, pathsByLanguage)) {
        List<Path> currentPaths = pathsByLanguage.getOrDefault(language, List.of());
        Set<Path> currentPathSet = new HashSet<>(currentPaths);
        List<Path> missingFiles =
            writer.getFilePathsInSourceRoot(sourceRoot, language).stream()
                .filter(path -> !currentPathSet.contains(path))
                .filter(path -> !retainedPathSet.contains(path))
                .sorted()
                .toList();
        refreshAfterDelete.addAll(writer.getRetainedFilePathsSharingDefinitionsWith(missingFiles));
        runMissingFileCleanupWithRetry(
            sourceRoot,
            language,
            () ->
                writer.deleteFilesMissingFromSource(
                    sourceRoot, currentPaths, retainedPaths, language));
      }
      refreshRetainedFilesAfterDelete(writer, refreshAfterDelete, currentFilesByPath, stats);
    }
  }

  void runMissingFileCleanupWithRetry(
      Path sourceRoot, SourceLanguage language, Runnable cleanupAction) {
    long backoffMs = FILE_TX_INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= FILE_TX_RETRY_ATTEMPTS; attempt++) {
      try {
        cleanupAction.run();
        return;
      } catch (RuntimeException e) {
        if (!GraphWriter.isRetryable(e) || attempt == FILE_TX_RETRY_ATTEMPTS) {
          throw e;
        }
        backoffMs =
            sleepBeforeFileRetry(sourceRoot.resolve(language.graphName()), attempt, e, backoffMs);
      }
    }
  }

  private List<SourceLanguage> languages() {
    return languageAdapters.stream()
        .flatMap(adapter -> adapter.staticLanguage().stream())
        .distinct()
        .toList();
  }

  private static List<SourceLanguage> sourceLanguages(List<SourceFile> files) {
    return files.stream().map(SourceFile::language).distinct().toList();
  }

  private List<SourceLanguage> languagesForCleanup(
      GraphWriter writer, Map<SourceLanguage, List<Path>> pathsByLanguage) {
    Set<SourceLanguage> cleanupLanguages = new LinkedHashSet<>(languages());
    cleanupLanguages.addAll(pathsByLanguage.keySet());
    cleanupLanguages.addAll(writer.getLanguagesInSourceRoot(sourceRoot));
    return List.copyOf(cleanupLanguages);
  }

  Optional<LanguageAdapter<?>> adapterFor(Path file) {
    return adapterFor(file, languageAdapters);
  }

  private Optional<LanguageAdapter<?>> adapterFor(Path file, List<LanguageAdapter<?>> adapters) {
    Path localFile = LanguageAdapter.localPath(sourceRoot, file);
    return adapters.stream().filter(adapter -> adapter.accepts(localFile)).findFirst();
  }

  Optional<LanguageAdapter<?>> adapterForDeletedPath(Path file) {
    Path localFile = LanguageAdapter.localPath(sourceRoot, file);
    return languageAdapters.stream()
        .filter(adapter -> adapter.acceptsDeletedPath(localFile))
        .findFirst();
  }

  private Optional<SourceFile> sourceFileFor(
      Path file, Map<Path, SourceFile> currentFilesByPath, GraphWriter writer) {
    SourceFile currentFile = currentFilesByPath.get(file);
    if (currentFile != null) {
      return Optional.of(currentFile);
    }
    return adapterFor(file)
        .map(adapter -> new SourceFile(file, adapterForRetainedFile(file, adapter, writer)));
  }

  private LanguageAdapter<?> adapterForRetainedFile(
      Path file, LanguageAdapter<?> adapter, GraphWriter writer) {
    return rebaseAdapterForRetainedFile(file, adapter, writer);
  }

  private <T> LanguageAdapter<T> rebaseAdapterForRetainedFile(
      Path file, LanguageAdapter<T> adapter, GraphWriter writer) {
    return writer
        .getSourceRootHint(file, adapter.language(file))
        .flatMap(hint -> sourceRootFromHint(file, adapter.language(file), hint))
        .map(adapter::forSourceRoot)
        .orElse(adapter);
  }

  private static Optional<Path> sourceRootFromHint(
      Path file, SourceLanguage language, String sourceRootHint) {
    if (SourceLanguage.JAVA.equals(language)) {
      return relativePathFromPackageName(file, sourceRootHint)
          .flatMap(relativePath -> sourceRootFromRelativePath(file, relativePath));
    }
    return sourceRootHint.isBlank()
        ? Optional.empty()
        : sourceRootFromRelativePath(file, Path.of(sourceRootHint).normalize());
  }

  private static Optional<Path> relativePathFromPackageName(Path file, String packageName) {
    Path fileName = file.getFileName();
    if (fileName == null) {
      return Optional.empty();
    }
    Path relativePath = fileName;
    if (!packageName.isBlank()) {
      String[] packageParts = packageName.split(Const.Symbols.DOT_REGEX);
      for (int index = packageParts.length - 1; index >= 0; index--) {
        if (packageParts[index].isBlank()) {
          return Optional.empty();
        }
        relativePath = Path.of(packageParts[index]).resolve(relativePath);
      }
    }
    return Optional.of(relativePath);
  }

  private static Optional<Path> sourceRootFromRelativePath(Path file, Path relativePath) {
    Path normalizedFile = file.normalize();
    if (!normalizedFile.endsWith(relativePath)) {
      return Optional.empty();
    }
    int rootNameCount = normalizedFile.getNameCount() - relativePath.getNameCount();
    if (rootNameCount < 0) {
      return Optional.empty();
    }
    Path root = normalizedFile.getRoot();
    for (int index = 0; index < rootNameCount; index++) {
      root =
          root == null
              ? normalizedFile.getName(index)
              : root.resolve(normalizedFile.getName(index));
    }
    return Optional.ofNullable(root);
  }

  private int ingestSequential(
      List<SourceFile> files,
      StoredFileState storedFiles,
      Map<LanguageAdapter<?>, RuntimeException> failedAdapterPreparations,
      Collection<Path> retainedSourcePaths,
      IngestionRunStats stats,
      IngestionProgress progress) {
    int failures = 0;
    int done = 0;
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project, stats, analysisCacheKey);
      writer.setRetainedSourcePaths(retainedSourcePaths);
      for (SourceFile file : files) {
        PreparedFile prepared =
            prepareFileForIngestion(file, storedFiles, failedAdapterPreparations, stats);
        if (!writePreparedFile(writer, prepared)) {
          failures++;
        }
        done++;
        progress.update(done);
      }
    }
    return failures;
  }

  /**
   * Returns {@code true} when incremental mode is active and the file's filesystem {@code
   * lastModified} matches the value stored in {@code mtimeCache}, meaning no re-ingest is needed.
   */
  private boolean isFileUnchanged(Path file, StoredFileState storedFiles) {
    if (!incremental || !storedFiles.reliableExistingPaths()) {
      return false;
    }
    Long storedModified = storedFiles.lastModifiedByPath().get(file.toString());
    if (storedModified == null || storedModified <= 0) {
      return false;
    }
    if (storedFiles.pathsMissingCodeChunks().contains(file.toString())) {
      return false;
    }
    try {
      return Files.getLastModifiedTime(file).toMillis() == storedModified;
    } catch (IOException _) {
      return false;
    }
  }

  /**
   * Parses one source file, then commits cleanup and replacement writes in an explicit per-file
   * transaction.
   *
   * @return true on success (or skip), false if parsing or graph write fails
   */
  boolean ingestFileBatched(
      GraphWriter writer,
      SourceFile sourceFile,
      StoredFileState storedFiles,
      IngestionRunStats stats) {
    return writePreparedFile(writer, prepareFileForIngestion(sourceFile, storedFiles, stats));
  }

  private PreparedFile prepareFileForIngestion(
      SourceFile sourceFile, StoredFileState storedFiles, IngestionRunStats stats) {
    return prepareFileForIngestion(sourceFile, storedFiles, Map.of(), stats);
  }

  private PreparedFile prepareFileForIngestion(
      SourceFile sourceFile,
      StoredFileState storedFiles,
      Map<LanguageAdapter<?>, RuntimeException> failedAdapterPreparations,
      IngestionRunStats stats) {
    return prepareFileForIngestion(
        sourceFile.adapter(),
        sourceFile.path(),
        storedFiles,
        failedAdapterPreparations.get(sourceFile.adapter()),
        stats);
  }

  private <T> PreparedFile prepareFileForIngestion(
      LanguageAdapter<T> adapter,
      Path path,
      StoredFileState storedFiles,
      RuntimeException adapterPreparationFailure,
      IngestionRunStats stats) {
    if (isFileUnchanged(path, storedFiles)) {
      log.debug("Skipping unchanged file: {}", path);
      return new PreparedSkip(path);
    }
    if (adapterPreparationFailure != null) {
      log.warn("Failed to prepare {}: {}", path, adapterPreparationFailure.getMessage());
      return new PreparedFailure(path);
    }

    log.atDebug()
        .setMessage("Ingesting {} (project={}, language={})")
        .addArgument(path)
        .addArgument(project)
        .addArgument(adapter::displayName)
        .log();

    T parsed;
    SourceFileDefinitions definitions;
    long startedNanos = System.nanoTime();
    try {
      Optional<T> parsedOpt = adapter.parse(path);
      if (parsedOpt.isEmpty()) {
        return new PreparedFailure(path);
      }
      parsed = parsedOpt.get();
      definitions = adapter.collectDefinitions(parsed);
    } catch (RuntimeException e) {
      log.warn("Failed to prepare {}: {}", path, e.getMessage());
      return new PreparedFailure(path);
    } finally {
      stats.recordPhaseNanos(IngestionRunStats.PHASE_PARSE, System.nanoTime() - startedNanos);
    }
    return new PreparedWrite<>(path, adapter, parsed, definitions);
  }

  boolean writePreparedFile(GraphWriter writer, PreparedFile prepared) {
    return switch (prepared) {
      case PreparedFailure _ -> {
        writer.stats().recordFailedFile();
        yield false;
      }
      case PreparedSkip _ -> {
        writer.stats().recordSkippedFile();
        yield true;
      }
      case PreparedWrite<?> write -> writePreparedWrite(writer, write);
    };
  }

  private <T> boolean writePreparedWrite(GraphWriter writer, PreparedWrite<T> prepared) {
    long backoffMs = FILE_TX_INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= FILE_TX_RETRY_ATTEMPTS; attempt++) {
      writer.beginFileTransaction();
      try {
        long cleanupStartedNanos = System.nanoTime();
        try {
          writer.deleteStaleDefinitionsForFile(prepared.path(), prepared.definitions());
        } finally {
          writer
              .stats()
              .recordPhaseNanos(
                  IngestionRunStats.PHASE_CLEANUP, System.nanoTime() - cleanupStartedNanos);
        }
        long writeStartedNanos = System.nanoTime();
        boolean success;
        try {
          success = prepared.adapter().write(writer, prepared.path(), prepared.parsed());
        } finally {
          writer
              .stats()
              .recordPhaseNanos(
                  IngestionRunStats.PHASE_WRITE, System.nanoTime() - writeStartedNanos);
        }
        if (success) {
          writer.commitFileTransaction();
          writer.stats().recordIngestedFile();
          writer.stats().recordChangedDefinitions(prepared.definitions());
        } else {
          writer.stats().recordFailedFile();
          writer.rollbackFileTransaction();
        }
        return success;
      } catch (RuntimeException e) {
        writer.rollbackFileTransaction();
        if (!GraphWriter.isRetryable(e) || attempt == FILE_TX_RETRY_ATTEMPTS) {
          log.warn("Failed to ingest {}: {}", prepared.path(), e.getMessage());
          writer.stats().recordFailedFile();
          return false;
        }
        backoffMs = sleepBeforeFileRetry(prepared.path(), attempt, e, backoffMs);
      }
    }
    return false;
  }

  private long sleepBeforeFileRetry(Path file, int attempt, RuntimeException e, long backoffMs) {
    long jitterMs = Math.floorMod(file.hashCode() + attempt * 31L, backoffMs);
    long delayMs = backoffMs + jitterMs;
    log.debug(
        "Retrying {} after transaction conflict on attempt {} in {} ms: {}",
        file,
        attempt,
        delayMs,
        e.getMessage());
    try {
      TimeUnit.MILLISECONDS.sleep(delayMs);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted during file retry", ie);
    }
    return Math.clamp(backoffMs * 2, 0L, FILE_TX_MAX_BACKOFF_MS);
  }

  @SuppressWarnings(value = {Const.Warnings.COGNITIVE_COMPLEXITY})
  private int ingestParallel(
      List<SourceFile> files,
      StoredFileState storedFiles,
      Map<LanguageAdapter<?>, RuntimeException> failedAdapterPreparations,
      Collection<Path> retainedSourcePaths,
      IngestionRunStats stats,
      IngestionProgress progress) {
    try {
      return ingestParallelTransactional(
          files, storedFiles, failedAdapterPreparations, retainedSourcePaths, stats, progress);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted during ingestion", e);
    }
  }

  private int ingestParallelTransactional(
      List<SourceFile> files,
      StoredFileState storedFiles,
      Map<LanguageAdapter<?>, RuntimeException> failedAdapterPreparations,
      Collection<Path> retainedSourcePaths,
      IngestionRunStats stats,
      IngestionProgress progress)
      throws InterruptedException {
    if (files.isEmpty()) {
      return 0;
    }
    AtomicInteger threadCounter = new AtomicInteger();
    ExecutorService pool =
        Executors.newFixedThreadPool(
            threads,
            r -> {
              Thread t = new Thread(r, "ingester-" + threadCounter.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
    CompletionService<PreparedFile> cs = new ExecutorCompletionService<>(pool);
    for (SourceFile file : files) {
      cs.submit(
          () -> {
            try {
              return prepareFileForIngestion(file, storedFiles, failedAdapterPreparations, stats);
            } catch (RuntimeException e) {
              log.warn("Thread failure on {}: {}", file.path(), e.getMessage());
              return new PreparedFailure(file.path());
            }
          });
    }
    pool.shutdown();
    long waitDeadlineNanos = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();

    int failures = 0;
    int done = 0;
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project, stats, analysisCacheKey);
      writer.setRetainedSourcePaths(retainedSourcePaths);
      for (int i = 0; i < files.size(); i++) {
        long remainingNanos = waitDeadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
          log.warn("Ingestion did not complete within {}.", SHUTDOWN_TIMEOUT);
          pool.shutdownNow();
          throw new ProcessingException("Parallel ingestion preparation timed out");
        }
        Future<PreparedFile> future = cs.poll(remainingNanos, TimeUnit.NANOSECONDS);
        if (future == null) {
          log.warn("Ingestion did not complete within {}.", SHUTDOWN_TIMEOUT);
          pool.shutdownNow();
          throw new ProcessingException("Parallel ingestion preparation timed out");
        }
        PreparedFile prepared = takePrepared(future);
        long writeStartNanos = System.nanoTime();
        if (!writePreparedFile(writer, prepared)) {
          log.info("Failure preparing file: {}", prepared.path());
          failures++;
        }
        waitDeadlineNanos += System.nanoTime() - writeStartNanos;
        done++;
        progress.update(done);
      }
    } finally {
      if (!pool.isTerminated()) {
        pool.shutdownNow();
      }
    }
    return failures;
  }

  private static PreparedFile takePrepared(Future<PreparedFile> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted during ingestion", e);
    } catch (ExecutionException e) {
      throw new ProcessingException("Unexpected execution failure during ingestion", e);
    }
  }
}
