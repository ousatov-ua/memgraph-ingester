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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * Walks the source tree and dispatches files to a {@link LanguageAdapter} and {@link GraphWriter}.
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
  private final LanguageAdapter languageAdapter;
  private boolean incremental;

  /**
   * @param sourceRoot   root directory to walk for {@code .java} files
   * @param project      project name used to scope all graph writes
   * @param threads      number of parallel worker threads (1 = sequential)
   * @param driver       shared Bolt driver — not closed by this orchestrator
   * @param parseService per-thread parser service
   */
  public IngestionOrchestrator(Path sourceRoot, String project, int threads, Driver driver,
      ParseService parseService) {
    this(sourceRoot, project, threads, driver, new JavaLanguageAdapter(parseService));
  }

  /**
   * @param sourceRoot      root directory to walk
   * @param project         project name used to scope all graph writes
   * @param threads         number of parallel worker threads (1 = sequential)
   * @param driver          shared Bolt driver — not closed by this orchestrator
   * @param languageAdapter parser and graph writer adapter for the selected language
   */
  public IngestionOrchestrator(Path sourceRoot, String project, int threads, Driver driver,
      LanguageAdapter languageAdapter) {
    this.sourceRoot = sourceRoot;
    this.project = project;
    this.threads = threads;
    this.driver = driver;
    this.languageAdapter = languageAdapter;
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
      log.info("--incremental is incompatible with --wipe-all / --wipe-project-code: wiping removes"
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
      bootstrapWriter.upsertProject(sourceRoot);
      log.info("Upserted :Project -> :Code and :Project -> :Memory anchors for '{}'", project);
      bootstrapWriter.backfillMethodOwnerMetadata();
      log.info("Backfilled :Method owner metadata for '{}'", project);
    }

    List<Path> files = languageAdapter.discoverFiles(sourceRoot);
    log.atInfo().setMessage("Found {} {} files. Ingesting with {} thread(s).")
        .addArgument(files::size).addArgument(languageAdapter::displayName).addArgument(threads)
        .log();

    Map<String, Long> mtimeCache = Map.of();
    if (incremental) {
      try (Session session = driver.session()) {
        mtimeCache = new GraphWriter(session, project).getAllFileLastModified(files);
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
      postWriter.deletePhantomMethods();
      log.info("Removed phantom external Method nodes for '{}'", project);
      postWriter.resolveCodeRefs();
      log.info("Refreshed :CodeRef resolution edges for '{}'", project);
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

          @SuppressWarnings("unchecked") WatchEvent<Path> ev = (WatchEvent<Path>) event;
          Path name = ev.context();
          Path child = dir.resolve(name);

          if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
            registerRecursive(child, watcher, keys);
          }

          if (languageAdapter.accepts(child)) {
            changedFiles.add(child);
          }
        }

        if (!changedFiles.isEmpty()) {

          // Debounce: wait a bit for more events (e.g. IDE multiple writes)
          TimeUnit.MILLISECONDS.sleep(500);
          log.info("Watch event: detected changes in {} file(s). Re-ingesting...",
              changedFiles.size());
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
        if (Files.exists(file) && ingestFile(writer, file)) {
          anySuccess = true;
        }
      }
      if (anySuccess) {
        writer.deletePhantomMethods();
        writer.resolveCodeRefs();
        log.info("Watch re-ingestion complete.");
      }
    }
  }

  private void registerRecursive(Path start, WatchService watcher, Map<WatchKey, Path> keys)
      throws IOException {
    Files.walkFileTree(start, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attrs)
          throws IOException {
        WatchKey key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
        keys.put(key, dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  private int ingestSequential(List<Path> files, Map<String, Long> mtimeCache) {
    int failures = 0;
    int total = files.size();
    int step = Math.clamp(total / PROGRESS_DIVISOR, 1, 100);
    int done = 0;
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project);
      for (Path file : files) {
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
   * Returns {@code true} when incremental mode is active and the file's filesystem
   * {@code lastModified} matches the value stored in {@code mtimeCache}, meaning no re-ingest is
   * needed.
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
  private boolean ingestFileBatched(GraphWriter writer, Path file, Map<String, Long> mtimeCache) {
    if (isFileUnchanged(file, mtimeCache)) {
      log.debug("Skipping unchanged file: {}", file);
      return true;
    }
    writer.beginFileTransaction();
    try {
      boolean success = ingestFile(writer, file);
      if (success) {
        writer.commitFileTransaction();
      } else {
        writer.rollbackFileTransaction();
      }
      return success;
    } catch (Exception e) {
      writer.rollbackFileTransaction();
      log.warn("Failed to ingest {}: {}", file, e.getMessage());
      return false;
    }
  }

  @SuppressWarnings(value = {"java:S3776"})
  private int ingestParallel(List<Path> files, Map<String, Long> mtimeCache)
      throws InterruptedException {
    CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
    ThreadLocal<GraphWriter> threadWriter = ThreadLocal.withInitial(() -> {
      Session s = driver.session();
      sessions.add(s);
      return new GraphWriter(s, project);
    });

    AtomicInteger threadCounter = new AtomicInteger();
    @SuppressWarnings("java:S2095") ExecutorService pool = Executors.newFixedThreadPool(threads,
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
      for (Path file : files) {
        pool.submit(() -> {
          try {
            if (!ingestFileChecked(threadWriter.get(), file, mtimeCache)) {
              failures.incrementAndGet();
            }
          } catch (Exception e) {
            log.warn("Thread failure on {}: {}", file, e.getMessage());
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
   * Parses and ingests a single file. In incremental mode, skips files whose filesystem
   * {@code lastModified} matches the value stored in {@code mtimeCache}.
   *
   * @param mtimeCache pre-loaded map of path → stored lastModified (populated at run-start when
   *                   incremental is true, otherwise empty)
   * @return true on success (or skip), false if parsing or graph write fails
   */
  private boolean ingestFileChecked(GraphWriter writer, Path file, Map<String, Long> mtimeCache) {
    if (isFileUnchanged(file, mtimeCache)) {
      log.debug("Skipping unchanged file: {}", file);
      return true;
    }
    return ingestFile(writer, file);
  }

  /**
   * Parses a single file through the selected language adapter and writes all structural nodes.
   * Java call edges still use placeholder callee nodes that are later upgraded or cleaned up by
   * {@link GraphWriter#deletePhantomMethods()}; JavaScript call edges are emitted only when the
   * analyzer can identify an in-project callee signature.
   *
   * @return true on success, false if parsing or graph write fails
   */
  private boolean ingestFile(GraphWriter writer, Path file) {
    log.atDebug().setMessage("Ingesting {} (project={}, language={})").addArgument(file)
        .addArgument(project).addArgument(languageAdapter::displayName).log();
    return languageAdapter.ingestFile(writer, file);
  }
}
