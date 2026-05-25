package io.github.ousatov.tools.memgraph.exe.ingestion;

import io.github.ousatov.tools.memgraph.IngesterCli;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.adapter.JavaLanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.analyze.ParseService;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionMetricsCollector;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.schema.Memgraph;
import io.github.ousatov.tools.memgraph.vo.Settings;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

  private static final long SHUTDOWN_TIMEOUT_MINUTES = 10L;
  private static final int FILE_TX_RETRY_ATTEMPTS = 16;
  private static final long FILE_TX_INITIAL_BACKOFF_MS = 10L;
  private static final long FILE_TX_MAX_BACKOFF_MS = 1_000L;
  private static final int PROGRESS_DIVISOR = 10;
  private final Path sourceRoot;
  private final String project;
  private final int threads;
  private final Driver driver;
  private final List<LanguageAdapter<?>> languageAdapters;
  private final Set<Path> pendingWatchFiles = new LinkedHashSet<>();
  private boolean incremental;

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
    log.info("Proceeding with ingestion, settings: {}", settings);
    this.incremental = settings.incremental();
    IngestionRunStats stats = new IngestionRunStats(threads);
    if (incremental && (settings.wipeAllData() || settings.wipeProjectCode())) {
      log.info(
          "--incremental is incompatible with --wipe-all / --wipe-project-code: wiping removes"
              + " stored timestamps, so incremental mode will be disabled for this run.");
      incremental = false;
    }
    try (Session bootstrap = driver.session()) {
      GraphWriter bootstrapWriter = new GraphWriter(bootstrap, project, stats);
      if (settings.wipeAllData()) {
        Memgraph.wipeAllData(bootstrap);
        log.info("Wiped all data from Memgraph");
      }
      if (settings.applySchema()) {
        Memgraph.applySchema(bootstrap);
        log.info("Applying schema to Memgraph");
      } else if (!Memgraph.hasLanguageScopedCodeSchema(bootstrap)) {
        Memgraph.applySchema(bootstrap);
        log.info("Applied language-scoped schema migration to Memgraph");
      }
      if (settings.wipeProjectCode()) {
        log.info("Wiping existing code graph for project '{}'...", project);
        bootstrapWriter.wipe();
      }
      if (settings.wipeProjectMemories()) {
        log.info("Wiping existing memory graph for project '{}'...", project);
        bootstrapWriter.wipeMemories();
      }
      bootstrapWriter.upsertProject(sourceRoot, languages());
      log.info(
          "Upserted :Project -> :Language -> :Code and :Project -> :Memory anchors for '{}'",
          project);
      bootstrapWriter.backfillMethodOwnerMetadata();
      log.info("Backfilled :Method owner metadata for '{}'", project);
    }

    List<SourceFile> files = discoverSourceFiles();
    stats.setTotalFiles(files.size());
    List<Path> retainedSourcePaths = retainedSourcePaths(files, stats);
    log.atInfo()
        .setMessage(
            "Found {} supported source files across {} adapter(s). Ingesting with {} thread(s).")
        .addArgument(files::size)
        .addArgument(languageAdapters::size)
        .addArgument(threads)
        .log();

    StoredFileState storedFiles = preloadStoredFileState(files);
    if (incremental) {
      log.info(
          "Pre-loaded {} stored file timestamps for incremental mode.",
          storedFiles.lastModifiedByPath().size());
    }

    int failures;
    if (threads == 1) {
      failures = ingestSequential(files, storedFiles, retainedSourcePaths, stats);
    } else {
      failures = ingestParallel(files, storedFiles, retainedSourcePaths, stats);
    }

    if (failures == 0) {
      deleteMissingSourceFiles(files, retainedSourcePaths, stats);
    } else {
      log.warn(
          "Skipping missing-file cleanup because {} file(s) failed to ingest; existing graph"
              + " state will be kept for retry.",
          failures);
    }

    try (Session session = driver.session()) {
      GraphWriter postWriter = new GraphWriter(session, project, stats);
      refreshDerivedGraphArtifacts(postWriter);
      printMetrics(session);
      printPerformance(stats);
    }

    if (settings.watch()) {
      startWatchLoop();
    }
    return failures;
  }

  @SuppressWarnings({"java:S3776", "java:S135"})
  private void startWatchLoop() {
    log.info("Starting watch mode for {}...", sourceRoot);
    try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
      Map<WatchKey, Path> keys = new HashMap<>();
      registerRecursive(sourceRoot, watcher, keys);

      while (true) {
        WatchKey key = watcher.take();
        Path dir = keys.get(key);
        if (dir == null) {
          log.warn("WatchKey not recognized!");
          continue;
        }

        Set<Path> changedFiles = new HashSet<>();
        for (WatchEvent<?> event : key.pollEvents()) {
          WatchEvent.Kind<?> kind = event.kind();
          if (kind == StandardWatchEventKinds.OVERFLOW) {
            continue;
          }

          @SuppressWarnings("unchecked")
          WatchEvent<Path> ev = (WatchEvent<Path>) event;
          Path name = ev.context();
          Path child = dir.resolve(name);

          if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
            registerRecursive(child, watcher, keys);
          }

          if (adapterFor(child).isPresent()) {
            changedFiles.add(child);
          }
        }

        if (!changedFiles.isEmpty()) {

          // Debounce: wait a bit for more events (e.g. IDE multiple writes)
          TimeUnit.MILLISECONDS.sleep(500);
          log.info(
              "Watch event: detected changes in {} file(s). Re-ingesting...", changedFiles.size());
          ingestChangedFiles(changedFiles);
        }

        if (!key.reset()) {
          keys.remove(key);
          if (keys.isEmpty()) {
            break;
          }
        }
      }
    } catch (IOException e) {
      throw new ProcessingException("Watch service failed", e);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  void ingestChangedFiles(Set<Path> files) {
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project);
      Set<Path> watchFiles = watchFilesForProcessing(files);
      Optional<WatchSourceSnapshot> sourceSnapshot = sourceSnapshotForWatch(writer);
      if (sourceSnapshot.isEmpty()) {
        pendingWatchFiles.addAll(watchFiles);
        if (reconcileDeletedWatchFiles(watchFiles, writer)) {
          refreshDerivedGraphArtifacts(writer);
        }
        return;
      }
      pendingWatchFiles.clear();
      WatchSourceSnapshot snapshot = sourceSnapshot.get();
      writer.setRetainedSourcePaths(snapshot.retainedPaths());
      Map<Path, SourceFile> currentFilesByPath = new HashMap<>();
      snapshot.files().forEach(sourceFile -> currentFilesByPath.put(sourceFile.path(), sourceFile));
      boolean changedGraph = false;
      int updateFailures = 0;
      Set<Path> refreshAfterDelete = new LinkedHashSet<>();
      List<SourceFile> existingFiles =
          watchFiles.stream()
              .map(currentFilesByPath::get)
              .filter(Objects::nonNull)
              .sorted(Comparator.comparing(SourceFile::path))
              .toList();
      List<Path> deletedFiles =
          watchFiles.stream()
              .filter(file -> !currentFilesByPath.containsKey(file))
              .sorted()
              .toList();
      for (SourceFile file : existingFiles) {
        try {
          boolean updated = ingestFileBatched(writer, file, StoredFileState.empty());
          changedGraph |= updated;
          if (!updated) {
            updateFailures++;
          }
        } catch (RuntimeException e) {
          updateFailures++;
          log.warn("Failed to update graph for watch event on {}: {}", file.path(), e.getMessage());
        }
      }
      if (updateFailures == 0) {
        changedGraph |= processWatchDeletedFiles(writer, deletedFiles, refreshAfterDelete);
      } else if (!deletedFiles.isEmpty()) {
        log.warn(
            "Skipping watch delete cleanup for {} file(s) because {} file update(s) failed.",
            deletedFiles.size(),
            updateFailures);
      }
      refreshRetainedFilesAfterDelete(writer, refreshAfterDelete, currentFilesByPath);
      if (changedGraph) {
        refreshDerivedGraphArtifacts(writer);
        log.info("Watch re-ingestion complete.");
      }
    }
  }

  /**
   * Deletes stale graph state for each watch-deleted file. Returns true if any deletion changed the
   * graph.
   */
  private boolean processWatchDeletedFiles(
      GraphWriter writer, List<Path> deletedFiles, Set<Path> refreshAfterDelete) {
    boolean anyDeleted = false;
    for (Path file : deletedFiles) {
      if (adapterFor(file).isPresent()) {
        Optional<Set<Path>> sharedRetainedFiles =
            retainedFilesSharingDefinitionsWithRetry(
                file, writer::getRetainedFilePathsSharingDefinitionsWith);
        if (sharedRetainedFiles.isPresent()
            && deleteSourceFileWithRetry(file, writer::deleteSourceFile)) {
          anyDeleted = true;
          refreshAfterDelete.addAll(sharedRetainedFiles.get());
        }
      }
    }
    return anyDeleted;
  }

  private Optional<WatchSourceSnapshot> sourceSnapshotForWatch(GraphWriter writer) {
    try {
      List<SourceFile> files = discoverSourceFiles();
      return Optional.of(new WatchSourceSnapshot(files, retainedSourcePaths(files, writer)));
    } catch (RuntimeException e) {
      log.warn("Skipping watch re-ingestion because source snapshot failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private Set<Path> watchFilesForProcessing(Set<Path> files) {
    Set<Path> watchFiles = new LinkedHashSet<>(pendingWatchFiles);
    watchFiles.addAll(files);
    return watchFiles;
  }

  private boolean reconcileDeletedWatchFiles(Set<Path> files, GraphWriter writer) {
    try {
      List<SourceFile> existingFiles =
          files.stream()
              .filter(Files::exists)
              .flatMap(
                  file -> adapterFor(file).map(adapter -> new SourceFile(file, adapter)).stream())
              .toList();
      if (!existingFiles.isEmpty()) {
        log.warn(
            "Skipping watch delete fallback because {} changed file(s) still exist.",
            existingFiles.size());
        return false;
      }
      writer.setRetainedSourcePaths(retainedSourcePaths(existingFiles, writer));
      boolean changedGraph = false;
      Set<Path> refreshAfterDelete = new LinkedHashSet<>();
      for (Path file : files.stream().filter(file -> !Files.exists(file)).sorted().toList()) {
        if (adapterFor(file).isPresent()) {
          Optional<Set<Path>> sharedRetainedFiles =
              retainedFilesSharingDefinitionsWithRetry(
                  file, writer::getRetainedFilePathsSharingDefinitionsWith);
          if (sharedRetainedFiles.isPresent()
              && deleteSourceFileWithRetry(file, writer::deleteSourceFile)) {
            changedGraph = true;
            refreshAfterDelete.addAll(sharedRetainedFiles.get());
          }
        }
      }
      refreshRetainedFilesAfterDelete(writer, refreshAfterDelete, Map.of());
      return changedGraph;
    } catch (RuntimeException e) {
      log.warn("Could not reconcile watch delete fallback: {}", e.getMessage());
      return false;
    }
  }

  void refreshRetainedFilesAfterDelete(
      GraphWriter writer, Collection<Path> paths, Map<Path, SourceFile> currentFilesByPath) {
    for (Path path : paths.stream().sorted().toList()) {
      try {
        Optional<SourceFile> retainedFile = sourceFileFor(path, currentFilesByPath, writer);
        if (retainedFile.isEmpty()) {
          continue;
        }
        if (!ingestFileBatched(writer, retainedFile.get(), StoredFileState.empty())) {
          log.warn("Failed to refresh retained file after delete: {}", path);
        }
      } catch (RuntimeException e) {
        log.warn("Failed to refresh retained file after delete on {}: {}", path, e.getMessage());
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
          log.warn("Failed to update graph for watch delete on {}: {}", file, e.getMessage());
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
          log.warn(
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
  private void refreshDerivedGraphArtifacts(GraphWriter writer) {
    writer.resolvePendingCalls();
    log.info("Resolved pending owner/name CALLS edges for '{}'", project);
    writer.deletePhantomMethods();
    log.info("Removed phantom external Method nodes for '{}'", project);
    writer.deleteEmptyPackages();
    log.info("Removed empty Package nodes for '{}'", project);
    writer.resolveCodeRefs();
    log.info("Refreshed :CodeRef resolution edges for '{}'", project);
  }

  @SuppressWarnings({"java:S106", "java:S1181"})
  private void printMetrics(Session session) {
    try {
      System.out.print(IngestionMetricsCollector.collect(session, project).toMarkdownTable());
    } catch (RuntimeException | LinkageError e) {
      log.warn("Could not print ingestion metrics for '{}': {}", project, e.getMessage());
    }
  }

  @SuppressWarnings({"java:S106", "java:S1181"})
  private void printPerformance(IngestionRunStats stats) {
    try {
      System.out.print(stats.snapshot().toMarkdownTable());
    } catch (RuntimeException | LinkageError e) {
      log.warn("Could not print ingestion performance for '{}': {}", project, e.getMessage());
    }
  }

  private void registerRecursive(Path start, WatchService watcher, Map<WatchKey, Path> keys)
      throws IOException {
    Files.walkFileTree(
        start,
        new SimpleFileVisitor<>() {
          @Override
          public @NonNull FileVisitResult preVisitDirectory(
              @NonNull Path dir, @NonNull BasicFileAttributes attrs) throws IOException {
            if (!shouldVisitDirectory(dir, languageAdapters)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            WatchKey key =
                dir.register(
                    watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            keys.put(key, dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static boolean isNodeModulesDirectory(Path dir) {
    Path fileName = dir.getFileName();
    return fileName != null && "node_modules".equals(fileName.toString());
  }

  private boolean shouldVisitDirectory(Path dir, List<LanguageAdapter<?>> adapters) {
    Path localDir = LanguageAdapter.localPath(sourceRoot, dir);
    return !isNodeModulesDirectory(localDir)
        && adapters.stream().allMatch(adapter -> adapter.shouldVisitDirectory(localDir));
  }

  private List<SourceFile> discoverSourceFiles() {
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
                adapterFor(file, adapters)
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
      GraphWriter writer = new GraphWriter(session, project);
      Map<String, Long> preloaded = new HashMap<>();
      Map<SourceLanguage, List<Path>> pathsByLanguage = new LinkedHashMap<>();
      for (SourceFile file : files) {
        pathsByLanguage
            .computeIfAbsent(file.adapter().language(), ignored -> new ArrayList<>())
            .add(file.path());
      }
      for (var entry : pathsByLanguage.entrySet()) {
        preloaded.putAll(writer.getAllFileLastModified(entry.getValue(), entry.getKey()));
      }
      return new StoredFileState(Map.copyOf(preloaded), true);
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

  private List<Path> retainedSourcePaths(List<SourceFile> files, GraphWriter writer) {
    Set<Path> retainedPaths = new LinkedHashSet<>();
    files.stream().map(SourceFile::path).forEach(retainedPaths::add);
    writer.getFilePathsInSourceRoot(sourceRoot).stream()
        .filter(path -> !retainedPaths.contains(path))
        .filter(Files::exists)
        .sorted()
        .forEach(retainedPaths::add);
    writer.getRetainedFilePathsOutsideSourceRoot(sourceRoot).stream()
        .filter(Files::exists)
        .sorted()
        .forEach(retainedPaths::add);
    return List.copyOf(retainedPaths);
  }

  private void deleteMissingSourceFiles(
      List<SourceFile> files, Collection<Path> retainedPaths, IngestionRunStats stats) {
    Map<SourceLanguage, List<Path>> pathsByLanguage = new LinkedHashMap<>();
    Map<Path, SourceFile> currentFilesByPath = new LinkedHashMap<>();
    for (SourceFile file : files) {
      currentFilesByPath.put(file.path(), file);
      pathsByLanguage
          .computeIfAbsent(file.adapter().language(), ignored -> new ArrayList<>())
          .add(file.path());
    }
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project, stats);
      writer.setRetainedSourcePaths(retainedPaths);
      Set<Path> retainedPathSet = new HashSet<>(retainedPaths);
      Set<Path> refreshAfterDelete = new LinkedHashSet<>();
      for (SourceLanguage language : languages()) {
        List<Path> currentPaths = pathsByLanguage.getOrDefault(language, List.of());
        Set<Path> currentPathSet = new HashSet<>(currentPaths);
        writer.getFilePathsInSourceRoot(sourceRoot, language).stream()
            .filter(path -> !currentPathSet.contains(path))
            .filter(path -> !retainedPathSet.contains(path))
            .sorted()
            .forEach(
                missingFile ->
                    refreshAfterDelete.addAll(
                        writer.getRetainedFilePathsSharingDefinitionsWith(missingFile)));
        runMissingFileCleanupWithRetry(
            sourceRoot,
            language,
            () ->
                writer.deleteFilesMissingFromSource(
                    sourceRoot, currentPaths, retainedPaths, language));
      }
      refreshRetainedFilesAfterDelete(writer, refreshAfterDelete, currentFilesByPath);
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
    return languageAdapters.stream().map(LanguageAdapter::language).distinct().toList();
  }

  private Optional<LanguageAdapter<?>> adapterFor(Path file) {
    return adapterFor(file, languageAdapters);
  }

  private Optional<LanguageAdapter<?>> adapterFor(Path file, List<LanguageAdapter<?>> adapters) {
    Path localFile = LanguageAdapter.localPath(sourceRoot, file);
    return adapters.stream().filter(adapter -> adapter.accepts(localFile)).findFirst();
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
        .getSourceRootHint(file, adapter.language())
        .flatMap(hint -> sourceRootFromHint(file, adapter.language(), hint))
        .map(adapter::forSourceRoot)
        .orElse(adapter);
  }

  private static Optional<Path> sourceRootFromHint(
      Path file, SourceLanguage language, String sourceRootHint) {
    return switch (language) {
      case JAVA ->
          relativePathFromPackageName(file, sourceRootHint)
              .flatMap(relativePath -> sourceRootFromRelativePath(file, relativePath));
      case JAVASCRIPT ->
          sourceRootHint.isBlank()
              ? Optional.empty()
              : sourceRootFromRelativePath(file, Path.of(sourceRootHint).normalize());
      case PYTHON ->
          sourceRootHint.isBlank()
              ? Optional.empty()
              : sourceRootFromRelativePath(file, Path.of(sourceRootHint).normalize());
    };
  }

  private static Optional<Path> relativePathFromPackageName(Path file, String packageName) {
    Path fileName = file.getFileName();
    if (fileName == null) {
      return Optional.empty();
    }
    Path relativePath = fileName;
    if (!packageName.isBlank()) {
      String[] packageParts = packageName.split("\\.");
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
      Collection<Path> retainedSourcePaths,
      IngestionRunStats stats) {
    int failures = 0;
    int total = files.size();
    int step = Math.clamp(total / PROGRESS_DIVISOR, 1, 100);
    int done = 0;
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project, stats);
      writer.setRetainedSourcePaths(retainedSourcePaths);
      for (SourceFile file : files) {
        if (!ingestFileBatched(writer, file, storedFiles)) {
          failures++;
        }
        done++;
        if (done % step == 0 || done == total) {
          log.info("Progress: {}/{} files", done, total);
        }
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
  private boolean ingestFileBatched(
      GraphWriter writer, SourceFile sourceFile, StoredFileState storedFiles) {
    return doIngestFileBatched(writer, sourceFile.adapter(), sourceFile.path(), storedFiles);
  }

  private <T> boolean doIngestFileBatched(
      GraphWriter writer, LanguageAdapter<T> adapter, Path path, StoredFileState storedFiles) {
    if (isFileUnchanged(path, storedFiles)) {
      log.debug("Skipping unchanged file: {}", path);
      writer.recordSkippedFile();
      return true;
    }

    log.atDebug()
        .setMessage("Ingesting {} (project={}, language={})")
        .addArgument(path)
        .addArgument(project)
        .addArgument(adapter::displayName)
        .log();

    T parsed;
    SourceFileDefinitions definitions;
    try {
      Optional<T> parsedOpt = adapter.parse(path);
      if (parsedOpt.isEmpty()) {
        writer.recordFailedFile();
        return false;
      }
      parsed = parsedOpt.get();
      definitions = adapter.collectDefinitions(parsed);
    } catch (RuntimeException e) {
      log.warn("Failed to prepare {}: {}", path, e.getMessage());
      writer.recordFailedFile();
      return false;
    }

    long backoffMs = FILE_TX_INITIAL_BACKOFF_MS;
    for (int attempt = 1; attempt <= FILE_TX_RETRY_ATTEMPTS; attempt++) {
      writer.beginFileTransaction();
      try {
        writer.deleteStaleDefinitionsForFile(path, definitions);
        boolean success = adapter.write(writer, path, parsed);
        if (success) {
          writer.commitFileTransaction();
          writer.recordIngestedFile();
        } else {
          writer.recordFailedFile();
          writer.rollbackFileTransaction();
        }
        return success;
      } catch (RuntimeException e) {
        writer.rollbackFileTransaction();
        if (!GraphWriter.isRetryable(e) || attempt == FILE_TX_RETRY_ATTEMPTS) {
          log.warn("Failed to ingest {}: {}", path, e.getMessage());
          writer.recordFailedFile();
          return false;
        }
        backoffMs = sleepBeforeFileRetry(path, attempt, e, backoffMs);
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
    return Math.min(backoffMs * 2, FILE_TX_MAX_BACKOFF_MS);
  }

  @SuppressWarnings(value = {"java:S3776"})
  private int ingestParallel(
      List<SourceFile> files,
      StoredFileState storedFiles,
      Collection<Path> retainedSourcePaths,
      IngestionRunStats stats) {
    try {
      return ingestParallelTransactional(files, storedFiles, retainedSourcePaths, stats);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted during ingestion", e);
    }
  }

  private int ingestParallelTransactional(
      List<SourceFile> files,
      StoredFileState storedFiles,
      Collection<Path> retainedSourcePaths,
      IngestionRunStats stats)
      throws InterruptedException {
    if (files.isEmpty()) {
      return 0;
    }
    CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
    ThreadLocal<GraphWriter> threadWriter =
        ThreadLocal.withInitial(
            () -> {
              Session s = driver.session();
              sessions.add(s);
              GraphWriter writer = new GraphWriter(s, project, stats);
              writer.setRetainedSourcePaths(retainedSourcePaths);
              return writer;
            });

    AtomicInteger threadCounter = new AtomicInteger();
    @SuppressWarnings("java:S2095")
    ExecutorService pool =
        Executors.newFixedThreadPool(
            threads,
            r -> {
              Thread t = new Thread(r, "ingester-" + threadCounter.incrementAndGet());
              t.setDaemon(true);
              return t;
            });

    AtomicInteger done = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    int total = files.size();
    int step = Math.clamp(total / PROGRESS_DIVISOR, 1, 100);

    try {
      CountDownLatch latch = new CountDownLatch(total);
      for (SourceFile file : files) {
        pool.submit(
            () ->
                runFileIngestionTask(
                    file, storedFiles, threadWriter.get(), failures, done, step, total, latch));
      }
      boolean completed = latch.await(SHUTDOWN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
      pool.shutdown();
      if (!completed) {
        log.warn("Ingestion did not complete within {} minutes.", SHUTDOWN_TIMEOUT_MINUTES);
        pool.shutdownNow();
        if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
          throw new ProcessingException(
              "Parallel ingestion timed out and worker threads did not stop cleanly");
        }
        failures.addAndGet(Math.max(1, total - done.get()));
      } else if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        pool.shutdownNow();
        throw new ProcessingException("Parallel ingestion workers did not stop cleanly");
      }
    } finally {
      for (Session s : sessions) {
        try {
          s.close();
        } catch (Exception e) {
          log.debug("Error closing worker session: {}", e.getMessage());
        }
      }
    }
    return failures.get();
  }

  /** Executes one file-ingestion unit inside a parallel worker thread. */
  @SuppressWarnings("java:S107")
  private void runFileIngestionTask(
      SourceFile file,
      StoredFileState storedFiles,
      GraphWriter writer,
      AtomicInteger failures,
      AtomicInteger done,
      int step,
      int total,
      CountDownLatch latch) {
    try {
      if (!ingestFileBatched(writer, file, storedFiles)) {
        failures.incrementAndGet();
      }
    } catch (Exception e) {
      log.warn("Thread failure on {}: {}", file.path(), e.getMessage());
      failures.incrementAndGet();
    } finally {
      int n = done.incrementAndGet();
      if (n % step == 0 || n == total) {
        log.info("Progress: {}/{} files", n, total);
      }
      latch.countDown();
    }
  }

  private record StoredFileState(
      Map<String, Long> lastModifiedByPath, boolean reliableExistingPaths) {

    static StoredFileState empty() {
      return new StoredFileState(Map.of(), true);
    }

    static StoredFileState unreliable() {
      return new StoredFileState(Map.of(), false);
    }
  }

  /** Source file paired with the adapter selected by extension. */
  private record SourceFile(Path path, LanguageAdapter<?> adapter) {
    // Record body intentionally empty.
  }

  /** Source files and retained paths captured from one watch-cycle snapshot. */
  private record WatchSourceSnapshot(List<SourceFile> files, List<Path> retainedPaths) {
    // Record body intentionally empty.
  }
}
