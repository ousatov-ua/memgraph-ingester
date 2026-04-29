package io.github.ousatov.tools.memgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.schema.Memgraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
   * @param wipeProjectCode if true, deletes this project's code graph before ingesting
   * @return number of failed files; 0 means complete success
   */
  public int run(boolean wipeProjectCode) {
    return run(false, false, wipeProjectCode, false);
  }

  /**
   * Runs the full ingestion and returns the number of files that failed.
   *
   * @param wipeAllData if true, deletes all data before ingesting
   * @param applySchema if true, applies schema first
   * @param wipe if true, deletes this project's code graph before ingesting
   * @return number of failed files; 0 means complete success
   */
  public int run(boolean wipeAllData, boolean applySchema, boolean wipe) {
    return run(wipeAllData, applySchema, wipe, false);
  }

  /**
   * Runs the full ingestion and returns the number of files that failed.
   *
   * @param wipeAllData if true, deletes all data before ingesting
   * @param applySchema if true, applies schema first
   * @param wipeProjectCode if true, deletes this project's code graph before ingesting
   * @param wipeProjectMemories if true, deletes this project's memory graph before ingesting
   * @return number of failed files; 0 means complete success
   */
  public int run(
      boolean wipeAllData,
      boolean applySchema,
      boolean wipeProjectCode,
      boolean wipeProjectMemories) {
    try (Session bootstrap = driver.session()) {
      GraphWriter bootstrapWriter = new GraphWriter(bootstrap, project);
      if (wipeAllData) {
        Memgraph.wipeAllData(bootstrap);
        log.info("Wiped all data from Memgraph");
      }
      if (applySchema) {
        Memgraph.applySchema(bootstrap);
        log.info("Applying schema to Memgraph");
      }
      if (wipeProjectCode) {
        log.info("Wiping existing code graph for project '{}'...", project);
        bootstrapWriter.wipe();
      }
      if (wipeProjectMemories) {
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

    if (threads == 1) {
      return ingestSequential(files);
    } else {
      try {
        return ingestParallel(files);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ProcessingException("Interrupted during ingestion", e);
      }
    }
  }

  private int ingestSequential(List<Path> files) {
    int failures = 0;
    int total = files.size();
    int step = Math.clamp(total / PROGRESS_DIVISOR, 1, 100);
    int done = 0;
    try (Session session = driver.session()) {
      GraphWriter writer = new GraphWriter(session, project);
      for (Path file : files) {
        if (!ingestFile(writer, file)) {
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

  @SuppressWarnings("java:S2095")
  private int ingestParallel(List<Path> files) throws InterruptedException {
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
    int total = files.size();
    int step = Math.clamp(total / PROGRESS_DIVISOR, 1, 100);

    try {
      for (Path file : files) {
        pool.submit(
            () -> {
              boolean success;
              try {
                success = ingestFile(threadWriter.get(), file);
              } catch (Exception e) {
                log.warn("Thread failure on {}: {}", file, e.getMessage());
                success = false;
              }
              if (!success) {
                failures.incrementAndGet();
              }
              int n = done.incrementAndGet();
              if (n % step == 0 || n == total) {
                log.info("Progress: {}/{} files", n, total);
              }
            });
      }
      pool.shutdown();
      if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        log.warn(
            "Ingestion tasks did not complete within {} minutes; forcing shutdown.",
            SHUTDOWN_TIMEOUT_MINUTES);
        pool.shutdownNow();
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

  /**
   * Parses and ingests a single file.
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
      log.warn("Failed to ingest {}: {}", file, e.getMessage());
      return false;
    }
  }
}
