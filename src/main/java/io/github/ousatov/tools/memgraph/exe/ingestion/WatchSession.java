package io.github.ousatov.tools.memgraph.exe.ingestion;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleOutput;
import io.github.ousatov.tools.memgraph.exe.output.ConsoleStatusLine;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.vo.ingestion.SourceFile;
import io.github.ousatov.tools.memgraph.vo.ingestion.StoredFileState;
import io.github.ousatov.tools.memgraph.vo.ingestion.WatchSourceSnapshot;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.neo4j.driver.Session;
import org.neo4j.driver.async.AsyncSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Long-running file-system watch loop that re-ingests files as they change.
 *
 * <p>The session owns the {@link WatchService}, the queue of pending watch events, and all
 * watch-only re-ingestion bookkeeping. It delegates per-file parse and write work back to the
 * owning {@link IngestionOrchestrator}.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings("java:S106")
final class WatchSession {

  private static final Logger log = LoggerFactory.getLogger(WatchSession.class);

  private static final long DEBOUNCE_MS = AppConfig.durationValue("watch.debounce").toMillis();

  private final IngestionOrchestrator orchestrator;
  private final Set<Path> pendingFiles = new LinkedHashSet<>();

  WatchSession(IngestionOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  /** Starts the watch loop and blocks the calling thread until the watcher closes. */
  @SuppressWarnings({Const.Warnings.COGNITIVE_COMPLEXITY, Const.Warnings.LOOP_CONTROL})
  void start() {
    Path sourceRoot = orchestrator.sourceRoot();
    log.debug("Starting watch mode for {}...", sourceRoot);
    try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
      Map<WatchKey, Path> keys = new HashMap<>();
      registerRecursive(sourceRoot, watcher, keys);

      String watchActivated = "Watch mode for " + sourceRoot + " activated.";
      log.info(watchActivated);
      ConsoleOutput.success(watchActivated);
      ConsoleOutput.hint("Press CTRL + C to stop.");
      int watchReingestionsApplied = 0;
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

          if (adapterForWatchEvent(child, kind).isPresent()) {
            changedFiles.add(child);
          }
        }

        if (!changedFiles.isEmpty()) {

          // Debounce: wait a bit for more events (e.g. IDE multiple writes)
          TimeUnit.MILLISECONDS.sleep(DEBOUNCE_MS);
          renderWatchStatus(
              "Watch event: detected changes in "
                  + changedFiles.size()
                  + " file(s). Re-ingesting...");
          watchReingestionsApplied = ingestChanges(changedFiles, watchReingestionsApplied);
        }

        if (!key.reset()) {
          keys.remove(key);
          if (keys.isEmpty()) {
            break;
          }
        }
      }
      finishWatchStatus();
    } catch (IOException e) {
      finishWatchStatus();
      throw new ProcessingException("Watch service failed", e);
    } catch (InterruptedException _) {
      finishWatchStatus();
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      if (isInterruptedFailure(e)) {
        finishWatchStatus();
        Thread.currentThread().interrupt();
        return;
      }
      throw e;
    }
  }

  private int ingestChanges(Set<Path> changedFiles, int watchReingestionsApplied) {
    try {
      ingestChangedFiles(changedFiles);
      watchReingestionsApplied++;
      renderWatchStatus("Watch re-ingestion applied " + watchReingestionsApplied + " times.");
    } catch (RuntimeException e) {
      finishWatchStatus();
      throw e;
    }
    return watchReingestionsApplied;
  }

  void ingestChangedFiles(Set<Path> files) {
    IngestionRunStats stats = new IngestionRunStats(orchestrator.threads());
    AsyncSession asyncSession = orchestrator.driver().session(AsyncSession.class);
    try (Session session = orchestrator.driver().session()) {
      GraphWriter writer =
          new GraphWriter(
              session,
              asyncSession,
              orchestrator.project(),
              stats,
              orchestrator.analysisCacheKey());
      Set<Path> watchFiles = watchFilesForProcessing(files);
      Optional<WatchSourceSnapshot> sourceSnapshot = sourceSnapshotForWatch(writer);
      if (sourceSnapshot.isEmpty()) {
        pendingFiles.addAll(watchFiles);
        if (reconcileDeletedWatchFiles(watchFiles, writer, stats)) {
          orchestrator.refreshDerivedGraphArtifacts(writer);
          orchestrator.refreshChunkEmbeddings(writer, true);
        }
        return;
      }
      pendingFiles.clear();
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
          boolean updated =
              orchestrator.ingestFileBatched(writer, file, StoredFileState.empty(), stats);
          changedGraph |= updated;
          if (!updated) {
            updateFailures++;
          }
        } catch (RuntimeException e) {
          updateFailures++;
          logWatchWarning(
              "Failed to update graph for watch event on {}: {}", file.path(), e.getMessage());
        }
      }
      if (updateFailures == 0) {
        changedGraph |= processWatchDeletedFiles(writer, deletedFiles, refreshAfterDelete);
      } else if (!deletedFiles.isEmpty()) {
        logWatchWarning(
            "Skipping watch delete cleanup for {} file(s) because {} file update(s) failed.",
            deletedFiles.size(),
            updateFailures);
      }
      orchestrator.refreshRetainedFilesAfterDelete(
          writer, refreshAfterDelete, currentFilesByPath, stats);
      if (changedGraph) {
        orchestrator.refreshDerivedGraphArtifacts(writer);
        orchestrator.refreshChunkEmbeddings(writer, true);
      }
    } finally {
      IngestionOrchestrator.closeAsyncSession(asyncSession);
    }
  }

  private boolean processWatchDeletedFiles(
      GraphWriter writer, List<Path> deletedFiles, Set<Path> refreshAfterDelete) {
    boolean anyDeleted = false;
    for (Path file : deletedFiles) {
      if (orchestrator.adapterForDeletedPath(file).isPresent()) {
        Optional<Set<Path>> sharedRetainedFiles =
            orchestrator.retainedFilesSharingDefinitionsWithRetry(
                file, path -> writer.getRetainedFilePathsSharingDefinitionsWith(Set.of(path)));
        if (sharedRetainedFiles.isPresent()
            && orchestrator.deleteSourceFileWithRetry(file, writer::deleteSourceFile)) {
          anyDeleted = true;
          refreshAfterDelete.addAll(sharedRetainedFiles.get());
        }
      }
    }
    return anyDeleted;
  }

  private Optional<WatchSourceSnapshot> sourceSnapshotForWatch(GraphWriter writer) {
    try {
      List<SourceFile> files = orchestrator.discoverSourceFiles();
      return Optional.of(
          new WatchSourceSnapshot(files, orchestrator.retainedSourcePaths(files, writer)));
    } catch (RuntimeException e) {
      logWatchWarning(
          "Skipping watch re-ingestion because source snapshot failed: {}", e.getMessage());
      return Optional.empty();
    }
  }

  private Set<Path> watchFilesForProcessing(Set<Path> files) {
    Set<Path> watchFiles = new LinkedHashSet<>(pendingFiles);
    watchFiles.addAll(files);
    return watchFiles;
  }

  private boolean reconcileDeletedWatchFiles(
      Set<Path> files, GraphWriter writer, IngestionRunStats stats) {
    try {
      List<SourceFile> existingFiles =
          files.stream()
              .filter(Files::exists)
              .flatMap(
                  file ->
                      orchestrator
                          .adapterFor(file)
                          .map(adapter -> new SourceFile(file, adapter))
                          .stream())
              .toList();
      if (!existingFiles.isEmpty()) {
        logWatchWarning(
            "Skipping watch delete fallback because {} changed file(s) still exist.",
            existingFiles.size());
        return false;
      }
      writer.setRetainedSourcePaths(orchestrator.retainedSourcePaths(existingFiles, writer));
      boolean changedGraph = false;
      Set<Path> refreshAfterDelete = new LinkedHashSet<>();
      for (Path file : files.stream().filter(file -> !Files.exists(file)).sorted().toList()) {
        if (orchestrator.adapterForDeletedPath(file).isPresent()) {
          Optional<Set<Path>> sharedRetainedFiles =
              orchestrator.retainedFilesSharingDefinitionsWithRetry(
                  file, path -> writer.getRetainedFilePathsSharingDefinitionsWith(Set.of(path)));
          if (sharedRetainedFiles.isPresent()
              && orchestrator.deleteSourceFileWithRetry(file, writer::deleteSourceFile)) {
            changedGraph = true;
            refreshAfterDelete.addAll(sharedRetainedFiles.get());
          }
        }
      }
      orchestrator.refreshRetainedFilesAfterDelete(writer, refreshAfterDelete, Map.of(), stats);
      return changedGraph;
    } catch (RuntimeException e) {
      logWatchWarning("Could not reconcile watch delete fallback: {}", e.getMessage());
      return false;
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
            if (!orchestrator.shouldVisitDirectory(dir)) {
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

  private Optional<LanguageAdapter<?>> adapterForWatchEvent(Path file, WatchEvent.Kind<?> kind) {
    Optional<LanguageAdapter<?>> adapter = orchestrator.adapterFor(file);
    if (adapter.isPresent()) {
      return adapter;
    }
    if (kind == StandardWatchEventKinds.ENTRY_DELETE
        || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
      return orchestrator.adapterForDeletedPath(file);
    }
    return Optional.empty();
  }

  static void renderWatchStatus(String message) {
    if (!log.isInfoEnabled()) {
      return;
    }
    log.info(message);
    ConsoleStatusLine.update(System.err, message);
  }

  private static void finishWatchStatus() {
    ConsoleStatusLine.finish(System.err);
  }

  static void logWatchWarning(String message, Object... arguments) {
    if (!log.isWarnEnabled()) {
      return;
    }
    ConsoleStatusLine.withFinishedLine(System.err, () -> log.warn(message, arguments));
  }

  static boolean isInterruptedFailure(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof InterruptedException) {
        return true;
      }
      String message = current.getMessage();
      if (message != null && message.contains("Thread interrupted")) {
        return true;
      }
    }
    return false;
  }
}
