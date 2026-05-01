package io.github.ousatov.tools.memgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.schema.Memgraph;
import io.github.ousatov.tools.memgraph.vo.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Walks the source tree and dispatches files to {@link ParseService} and {@link GraphWriter}.
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
  private final ParseService parseService;
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
    this.sourceRoot = sourceRoot;
    this.project = project;
    this.threads = threads;
    this.driver = driver;
    this.parseService = parseService;
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
      bootstrapWriter.upsertProject(sourceRoot);
      log.info("Upserted :Project -> :Code and :Project -> :Memory anchors for '{}'", project);
    }

    List<Path> files;
    try (Stream<Path> walk = Files.walk(sourceRoot)) {
      files = walk.filter(p -> p.toString().endsWith(".java")).toList();
    } catch (IOException e) {
      throw new ProcessingException("Cannot walk source root", e);
    }
    log.info("Found {} Java files. Ingesting with {} thread(s).", files.size(), threads);

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
      new GraphWriter(session, project).resolveCodeRefs();
      log.info("Refreshed :CodeRef resolution edges for '{}'", project);
    }
    return failures;
  }

  private int ingestSequential(List<Path> files, Map<String, Long> mtimeCache) {
    int failures = 0;
    int total = files.size();
    int step = Math.clamp(total / PROGRESS_DIVISOR, 1, 100);
    int done = 0;
    List<Path> successFiles = new ArrayList<>();
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project);
      for (Path file : files) {
        if (ingestFileBatched(writer, file, mtimeCache)) {
          successFiles.add(file);
        } else {
          failures++;
        }
        done++;
        if (done % step == 0 || done == total) {
          log.info("Progress (nodes): {}/{} files", done, total);
        }
      }
      log.info("Creating CALLS edges for {} files...", successFiles.size());
      for (Path file : successFiles) {
        if (!ingestFileCallEdges(writer, file)) {
          failures++;
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

  @SuppressWarnings(value = {"java:S2095", "java:S3776"})
  private int ingestParallel(List<Path> files, Map<String, Long> mtimeCache)
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
    CopyOnWriteArrayList<Path> successFiles = new CopyOnWriteArrayList<>();
    int total = files.size();
    int step = Math.clamp(total / PROGRESS_DIVISOR, 1, 100);

    try {
      // Phase 1: upsert all nodes
      CountDownLatch phase1Latch = new CountDownLatch(total);
      for (Path file : files) {
        pool.submit(
            () -> {
              try {
                boolean success = ingestFileChecked(threadWriter.get(), file, mtimeCache);
                if (success) {
                  successFiles.add(file);
                } else {
                  failures.incrementAndGet();
                }
              } catch (Exception e) {
                log.warn("Thread failure on {}: {}", file, e.getMessage());
                failures.incrementAndGet();
              } finally {
                int n = done.incrementAndGet();
                if (n % step == 0 || n == total) {
                  log.info("Progress (nodes): {}/{} files", n, total);
                }
                phase1Latch.countDown();
              }
            });
      }
      if (!phase1Latch.await(SHUTDOWN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        log.warn("Node ingestion did not complete within {} minutes.", SHUTDOWN_TIMEOUT_MINUTES);
      }

      // Phase 2: CALLS edges (all callee nodes now exist)
      log.info("Creating CALLS edges for {} files...", successFiles.size());
      CountDownLatch phase2Latch = new CountDownLatch(successFiles.size());
      for (Path file : successFiles) {
        pool.submit(
            () -> {
              try {
                if (!ingestFileCallEdges(threadWriter.get(), file)) {
                  failures.incrementAndGet();
                }
              } catch (Exception e) {
                log.warn("Thread failure creating CALLS for {}: {}", file, e.getMessage());
                failures.incrementAndGet();
              } finally {
                phase2Latch.countDown();
              }
            });
      }
      if (!phase2Latch.await(SHUTDOWN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        log.warn("CALLS ingestion did not complete within {} minutes.", SHUTDOWN_TIMEOUT_MINUTES);
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
  private boolean ingestFileChecked(GraphWriter writer, Path file, Map<String, Long> mtimeCache) {
    if (isFileUnchanged(file, mtimeCache)) {
      log.debug("Skipping unchanged file: {}", file);
      return true;
    }
    return ingestFile(writer, file);
  }

  /**
   * Parses a single file and writes all derived nodes and edges (except {@code CALLS}) to the
   * graph. {@code CALLS} edges are deferred to {@link #ingestFileCallEdges} so all callee nodes
   * exist first.
   *
   * @return true on success, false if parsing or graph write fails
   */
  private boolean ingestFile(GraphWriter writer, Path file) {
    var cuOpt = parseService.parse(file);
    if (cuOpt.isEmpty()) {
      return false;
    }
    CompilationUnit cu = cuOpt.get();
    String pkg = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
    try {
      log.debug("Ingesting {} (project={})", file, project);
      writer.upsertFile(file);
      writer.upsertPackage(pkg);
      cu.getTypes()
          .forEach(
              typeDecl -> {
                if (typeDecl instanceof ClassOrInterfaceDeclaration ci) {
                  writer.upsertType(file, pkg, ci);
                } else if (typeDecl instanceof EnumDeclaration en) {
                  writer.upsertEnum(file, pkg, en);
                } else if (typeDecl instanceof RecordDeclaration rec) {
                  writer.upsertRecord(file, pkg, rec);
                } else if (typeDecl instanceof AnnotationDeclaration ann) {
                  writer.upsertAnnotation(file, pkg, ann);
                }
              });
      return true;
    } catch (Exception e) {
      log.warn("Failed to ingest {}: {}", file, e.getMessage());
      return false;
    }
  }

  /**
   * Re-parses {@code file} and writes {@code CALLS} edges. Runs in a separate global phase after
   * all files have completed node ingestion, ensuring all callee Method nodes exist.
   *
   * @return true on success, false if parsing or edge creation fails
   */
  private boolean ingestFileCallEdges(GraphWriter writer, Path file) {
    var cuOpt = parseService.parse(file);
    if (cuOpt.isEmpty()) {
      return false;
    }
    CompilationUnit cu = cuOpt.get();
    String pkg = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
    try {
      cu.getTypes()
          .forEach(
              typeDecl -> {
                if (typeDecl instanceof ClassOrInterfaceDeclaration ci) {
                  writer.upsertTypeCallEdges(pkg, ci);
                } else if (typeDecl instanceof EnumDeclaration en) {
                  writer.upsertEnumCallEdges(pkg, en);
                } else if (typeDecl instanceof RecordDeclaration rec) {
                  writer.upsertRecordCallEdges(pkg, rec);
                }
              });
      return true;
    } catch (Exception e) {
      log.warn("Failed to create CALLS edges for {}: {}", file, e.getMessage());
      return false;
    }
  }
}
