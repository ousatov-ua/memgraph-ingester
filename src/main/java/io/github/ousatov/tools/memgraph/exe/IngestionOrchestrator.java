package io.github.ousatov.tools.memgraph.exe;

import io.github.ousatov.tools.memgraph.IngesterCli;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
  private static final int PROGRESS_DIVISOR = 10;

  private final Path sourceRoot;
  private final String project;
  private final int threads;
  private final Driver driver;
  private final List<LanguageAdapter> languageAdapters;
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
      LanguageAdapter languageAdapter) {
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
      List<LanguageAdapter> languageAdapters) {
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
    if (incremental && (settings.wipeAllData() || settings.wipeProjectCode())) {
      log.info(
          "--incremental is incompatible with --wipe-all / --wipe-project-code: wiping removes"
              + " stored timestamps, so incremental mode will be disabled for this run.");
      incremental = false;
    }
    try (Session bootstrap = driver.session()) {
      GraphWriter bootstrapWriter = new GraphWriter(bootstrap, project);
      if (settings.wipeAllData()) {
        Memgraph.wipeAllData(bootstrap);
        log.info("Wiped all data from Memgraph");
      }
      if (settings.applySchema()) {
        Memgraph.applySchema(bootstrap);
        log.info("Applying schema to Memgraph");
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
    log.atInfo()
        .setMessage(
            "Found {} supported source files across {} adapter(s). Ingesting with {} thread(s).")
        .addArgument(files::size)
        .addArgument(languageAdapters::size)
        .addArgument(threads)
        .log();

    Map<String, Long> mtimeCache = Map.of();
    if (incremental) {
      try (Session session = driver.session()) {
        List<Path> paths = files.stream().map(SourceFile::path).toList();
        mtimeCache = new GraphWriter(session, project).getAllFileLastModified(paths);
        log.info("Pre-loaded {} stored file timestamps for incremental mode.", mtimeCache.size());
      }
    }

    int failures;
    if (threads == 1) {
      failures = ingestSequential(files, mtimeCache);
    } else {
      try {
        failures = ingestParallel(files, mtimeCache);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ProcessingException("Interrupted during ingestion", e);
      }
    }

    try (Session session = driver.session()) {
      GraphWriter postWriter = new GraphWriter(session, project);
      refreshDerivedGraphArtifacts(postWriter);
      printMetrics(session);
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

  private void ingestChangedFiles(Set<Path> files) {
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project);
      boolean anySuccess = false;
      for (Path file : files) {
        Optional<LanguageAdapter> adapter = adapterFor(file);
        if (Files.exists(file)
            && adapter.isPresent()
            && ingestFile(writer, new SourceFile(file, adapter.get()))) {
          anySuccess = true;
        }
      }
      if (anySuccess) {
        refreshDerivedGraphArtifacts(writer);
        log.info("Watch re-ingestion complete.");
      }
    }
  }

  /** Refreshes graph artifacts that depend on all available code nodes being present. */
  private void refreshDerivedGraphArtifacts(GraphWriter writer) {
    writer.resolvePendingCalls();
    log.info("Resolved pending owner/name CALLS edges for '{}'", project);
    writer.deletePhantomMethods();
    log.info("Removed phantom external Method nodes for '{}'", project);
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

  private void registerRecursive(Path start, WatchService watcher, Map<WatchKey, Path> keys)
      throws IOException {
    Files.walkFileTree(
        start,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(
              @NonNull Path dir, @NonNull BasicFileAttributes attrs) throws IOException {
            if (!shouldVisitDirectory(dir)) {
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

  private boolean shouldVisitDirectory(Path dir) {
    return !isNodeModulesDirectory(dir)
        && languageAdapters.stream().anyMatch(adapter -> adapter.shouldVisitDirectory(dir));
  }

  private List<SourceFile> discoverSourceFiles() {
    Map<Path, SourceFile> byPath = new LinkedHashMap<>();
    List<SourceFile> discovered = new ArrayList<>();
    for (LanguageAdapter adapter : languageAdapters) {
      adapter
          .discoverFiles(sourceRoot)
          .forEach(file -> discovered.add(new SourceFile(file, adapter)));
    }
    discovered.stream()
        .sorted(Comparator.comparing(SourceFile::path))
        .forEach(sourceFile -> byPath.putIfAbsent(sourceFile.path(), sourceFile));
    return List.copyOf(byPath.values());
  }

  private List<SourceLanguage> languages() {
    return languageAdapters.stream().map(LanguageAdapter::language).distinct().toList();
  }

  private Optional<LanguageAdapter> adapterFor(Path file) {
    return languageAdapters.stream().filter(adapter -> adapter.accepts(file)).findFirst();
  }

  private int ingestSequential(List<SourceFile> files, Map<String, Long> mtimeCache) {
    int failures = 0;
    int total = files.size();
    int step = Math.clamp(total / PROGRESS_DIVISOR, 1, 100);
    int done = 0;
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project);
      for (SourceFile file : files) {
        if (!ingestFileBatched(writer, file, mtimeCache)) {
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
  private boolean isFileUnchanged(Path file, Map<String, Long> mtimeCache) {
    if (!incremental) {
      return false;
    }
    Long storedModified = mtimeCache.get(file.toString());
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
   * Wraps {@link #ingestFile} in an explicit per-file transaction so all writes for the file are
   * committed in a single Bolt round-trip. Safe only in sequential mode where there is exactly one
   * concurrent writer — no Memgraph MVCC conflicts are possible.
   *
   * @return true on success (or skip), false if parsing or graph write fails
   */
  private boolean ingestFileBatched(
      GraphWriter writer, SourceFile sourceFile, Map<String, Long> mtimeCache) {
    if (isFileUnchanged(sourceFile.path(), mtimeCache)) {
      log.debug("Skipping unchanged file: {}", sourceFile.path());
      return true;
    }
    writer.beginFileTransaction();
    try {
      boolean success = ingestFile(writer, sourceFile);
      if (success) {
        writer.commitFileTransaction();
      } else {
        writer.rollbackFileTransaction();
      }
      return success;
    } catch (Exception e) {
      writer.rollbackFileTransaction();
      log.warn("Failed to ingest {}: {}", sourceFile.path(), e.getMessage());
      return false;
    }
  }

  @SuppressWarnings(value = {"java:S3776"})
  private int ingestParallel(List<SourceFile> files, Map<String, Long> mtimeCache)
      throws InterruptedException {
    CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
    ThreadLocal<GraphWriter> threadWriter =
        ThreadLocal.withInitial(
            () -> {
              Session s = driver.session();
              sessions.add(s);
              return new GraphWriter(s, project);
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
            () -> {
              try {
                if (!ingestFileChecked(threadWriter.get(), file, mtimeCache)) {
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
            });
      }
      if (!latch.await(SHUTDOWN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        log.warn("Ingestion did not complete within {} minutes.", SHUTDOWN_TIMEOUT_MINUTES);
      }

      pool.shutdown();
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

  /**
   * Parses and ingests a single file. In incremental mode, skips files whose filesystem {@code
   * lastModified} matches the value stored in {@code mtimeCache}.
   *
   * @param mtimeCache pre-loaded map of path → stored lastModified (populated at run-start when
   *     incremental is true, otherwise empty)
   * @return true on success (or skip), false if parsing or graph write fails
   */
  private boolean ingestFileChecked(
      GraphWriter writer, SourceFile sourceFile, Map<String, Long> mtimeCache) {
    if (isFileUnchanged(sourceFile.path(), mtimeCache)) {
      log.debug("Skipping unchanged file: {}", sourceFile.path());
      return true;
    }
    return ingestFile(writer, sourceFile);
  }

  /**
   * Parses a single file through the selected language adapter and writes all structural nodes.
   * Fully resolved Java call edges may use placeholder callee nodes that are later upgraded or
   * cleaned up by {@link GraphWriter#deletePhantomMethods()}. Adapters can also persist deferred
   * owner/name calls that are resolved after the batch.
   *
   * @return true on success, false if parsing or graph write fails
   */
  private boolean ingestFile(GraphWriter writer, SourceFile sourceFile) {
    log.atDebug()
        .setMessage("Ingesting {} (project={}, language={})")
        .addArgument(sourceFile::path)
        .addArgument(project)
        .addArgument(sourceFile.adapter()::displayName)
        .log();
    return sourceFile.adapter().ingestFile(writer, sourceFile.path());
  }

  /** Source file paired with the adapter selected by extension. */
  private record SourceFile(Path path, LanguageAdapter adapter) {
    // Record body intentionally empty.
  }
}
