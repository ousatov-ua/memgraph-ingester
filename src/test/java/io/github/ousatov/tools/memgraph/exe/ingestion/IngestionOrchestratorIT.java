package io.github.ousatov.tools.memgraph.exe.ingestion;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.adapter.JavaLanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.LanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.PythonLanguageAdapter;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedPythonRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ParseService;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalyzer;
import io.github.ousatov.tools.memgraph.exe.metrics.IngestionRunStats;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.ctags.CtagsGraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.js.JsGraphWriter;
import io.github.ousatov.tools.memgraph.extension.MemgraphExtension;
import io.github.ousatov.tools.memgraph.extension.MemgraphInstance;
import io.github.ousatov.tools.memgraph.schema.Memgraph;
import io.github.ousatov.tools.memgraph.schema.MemgraphDriver;
import io.github.ousatov.tools.memgraph.vo.Settings;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

/**
 * End-to-end integration tests for {@link IngestionOrchestrator}.
 *
 * <p>Each test uses a unique project name and a freshly created temp source tree so tests are fully
 * independent. Cleanup happens in {@code @AfterEach}.
 *
 * @author Oleksii Usatov
 */
@ExtendWith(MemgraphExtension.class)
class IngestionOrchestratorIT {

  private static final String PROJECT_BASE =
      "test-orch-" + UUID.randomUUID().toString().substring(0, 8);

  private static Driver driver;
  private Path sourceDir;
  private String currentProject;

  private static final SourceLanguage RUBY = SourceLanguage.of("ruby", "Ruby");
  private static final String RUBY_SERVICE_FQN = "ruby.test.Service";

  @BeforeAll
  static void setupDriver(MemgraphInstance mg) {
    driver = MemgraphDriver.open(mg.getBoltUrl());
  }

  @AfterAll
  static void tearDownDriver() {
    driver.close();
  }

  private static Path buildSampleSourceTree() throws IOException {
    Path dir = Files.createTempDirectory("orch-src-");
    Path pkgDir = dir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Widget.java"),
        """
        package com.example;

        /** A widget. */
        public class Widget {
          private String name;

          /** Returns the name. */
          public String getName() {
            return name;
          }
        }
        """);
    Files.writeString(
        pkgDir.resolve("Describable.java"),
        """
        package com.example;

        /** Something that can describe itself. */
        public interface Describable {
          String describe();
        }
        """);
    return dir;
  }

  private static void wipeProjectCode(String project) {
    try (Session s = driver.session()) {
      s.run("MATCH (n) WHERE n.project = $p DETACH DELETE n", Map.of("p", project)).consume();
      s.run("MATCH (p:Project {name: $p}) DETACH DELETE p", Map.of("p", project)).consume();
    }
  }

  private static boolean classExists(String project, String fqn) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (c:Class {project: $p, fqn: $fqn}) RETURN count(c) AS n",
                  Map.of("p", project, "fqn", fqn))
              .single()
              .get("n")
              .asLong()
          > 0;
    }
  }

  private static boolean fileExistsInGraph(String project, Path file) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (f:File {project: $p, path: $path}) RETURN count(f) AS n",
                  Map.of("p", project, "path", file.toString()))
              .single()
              .get("n")
              .asLong()
          > 0;
    }
  }

  private static boolean methodExists(String project, String signature) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (m:Method {project: $p, signature: $sig}) RETURN count(m) AS n",
                  Map.of("p", project, "sig", signature))
              .single()
              .get("n")
              .asLong()
          > 0;
    }
  }

  private static List<String> methodSignatures(String project) {
    try (Session s = driver.session()) {
      return s.run(
              "MATCH (m:Method {project: $p}) RETURN m.signature AS sig ORDER BY sig",
              Map.of("p", project))
          .list(theRecord -> theRecord.get("sig").asString());
    }
  }

  private static List<String> codeChunkTexts(String project, Path file) {
    try (Session s = driver.session()) {
      return s.run(
              "MATCH (chunk:CodeChunk {project: $p, path: $path})"
                  + " RETURN chunk.text AS text ORDER BY chunk.sourceLabel, chunk.sourceId",
              Map.of("p", project, "path", file.toString()))
          .list(row -> row.get("text").asString());
    }
  }

  private static List<String> allCodeChunkSummaries(String project) {
    try (Session s = driver.session()) {
      return s.run(
              "MATCH (chunk:CodeChunk {project: $p})"
                  + " RETURN chunk.path + '|' + chunk.sourceLabel + '|' + chunk.sourceId AS summary"
                  + " ORDER BY summary",
              Map.of("p", project))
          .list(row -> row.get("summary").asString());
    }
  }

  private static long codeChunkCount(String project, Path file) {
    try (Session s = driver.session()) {
      return s.run(
              "MATCH (chunk:CodeChunk {project: $p, path: $path}) RETURN count(chunk) AS n",
              Map.of("p", project, "path", file.toString()))
          .single()
          .get("n")
          .asLong();
    }
  }

  private static boolean fieldExists(String project, String fqn) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (f:Field {project: $p, fqn: $fqn}) RETURN count(f) AS n",
                  Map.of("p", project, "fqn", fqn))
              .single()
              .get("n")
              .asLong()
          > 0;
    }
  }

  private static boolean callEdgeExists(String project, String caller, String callee) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (:Method {project: $p, signature: $caller})"
                      + "-[:CALLS]->(:Method {project: $p, signature: $callee})"
                      + " RETURN count(*) AS n",
                  Map.of("p", project, "caller", caller, "callee", callee))
              .single()
              .get("n")
              .asLong()
          > 0;
    }
  }

  private static boolean classExtendsEdgeExists(String project, String childFqn, String parentFqn) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (:Class {project: $p, fqn: $child})"
                      + "-[:EXTENDS]->(:Class {project: $p, fqn: $parent})"
                      + " RETURN count(*) AS n",
                  Map.of("p", project, "child", childFqn, "parent", parentFqn))
              .single()
              .get("n")
              .asLong()
          > 0;
    }
  }

  private static boolean systemPythonAvailable() {
    try {
      Process process =
          new ProcessBuilder(ManagedPythonRuntime.systemPythonExecutable(), "--version")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException _) {
      return false;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static List<String> moduleFqnsForFile(String project, Path file) {
    try (Session s = driver.session()) {
      return s.run(
              """
              MATCH (:File {project: $p, path: $path})-[:DEFINES]->(module {project: $p})
              WHERE module.modulePath IS NOT NULL
              RETURN module.fqn AS fqn
              ORDER BY fqn
              """,
              Map.of("p", project, "path", file.toString()))
          .list(row -> row.get("fqn").asString());
    }
  }

  private static void createClassCodeRef(String project, String fqn) {
    try (Session s = driver.session()) {
      s.run(
              "MATCH (m:Memory {project: $p})"
                  + " MERGE (d:Decision {id: 'DEC-watch-refers-to-class', project: $p})"
                  + " SET d.title = 'Watch class reference', d.status = 'accepted'"
                  + " MERGE (ref:CodeRef {project: $p, targetType: 'Class', key: $fqn})"
                  + " MERGE (m)-[:HAS_DECISION]->(d)"
                  + " MERGE (d)-[:REFERS_TO]->(ref)",
              Map.of("p", project, "fqn", fqn))
          .consume();
    }
  }

  private static boolean classCodeRefResolved(String project, String fqn) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (:CodeRef {project: $p, targetType: 'Class', key: $fqn})"
                      + "-[:RESOLVES_TO]->(:Class {project: $p, fqn: $fqn})"
                      + " RETURN count(*) AS n",
                  Map.of("p", project, "fqn", fqn))
              .single()
              .get("n")
              .asLong()
          > 0;
    }
  }

  private static boolean hasNoPhantomMethods(String project) {
    try (Session s = driver.session()) {
      return s.run(
                  "MATCH (m:Method {project: $p})"
                      + " WHERE m.startLine IS NULL"
                      + " RETURN count(m) AS n",
                  Map.of("p", project))
              .single()
              .get("n")
              .asLong()
          == 0;
    }
  }

  private static String helperSource() {
    return """
    package com.example;

    class Helper {
      static void go() {
        // Test helper body is intentionally empty.
      }
    }
    """;
  }

  private static String sharedWithHelperCallSource() {
    return """
    package com.example;

    class Shared {
      void serve() {
        Helper.go();
      }
    }
    """;
  }

  private static String sharedWithoutHelperCallSource() {
    return """
    package com.example;

    class Shared {
      void serve() {
        // Test source intentionally has no calls.
      }
    }
    """;
  }

  /**
   * Minimal JS/TS adapter used to verify multi-adapter orchestration without invoking Node.js.
   *
   * @author Oleksii Usatov
   */
  private static final class StubJsLanguageAdapter implements LanguageAdapter<Path> {

    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVASCRIPT;
    }

    @Override
    public boolean accepts(Path file) {
      return file.toString().endsWith(".ts");
    }

    @Override
    public Optional<Path> parse(Path file) {
      return Optional.of(file);
    }

    @Override
    public SourceFileDefinitions collectDefinitions(Path parsed) {
      return SourceFileDefinitions.of(
          List.of("js.test.App"), List.of(), List.of(), List.of("js.test.App.<init>()"), List.of());
    }

    @Override
    public boolean write(GraphWriter writer, Path file, Path parsed) {
      JsGraphWriter jsWriter = new JsGraphWriter(writer.dependencies());
      writer.upsertFile(file, language());
      writer.upsertPackage("js.test", language());
      jsWriter.upsertModule(file, "js.test", "js.test.App", "App", "app.ts", 1, 1);
      return true;
    }
  }

  /**
   * Adapter that records parser-runtime preparation calls.
   *
   * @author Oleksii Usatov
   */
  private static final class PrepareCountingAdapter implements LanguageAdapter<Path> {

    private final AtomicInteger prepares = new AtomicInteger();
    private final RuntimeException prepareFailure;

    private PrepareCountingAdapter() {
      this(null);
    }

    private PrepareCountingAdapter(RuntimeException prepareFailure) {
      this.prepareFailure = prepareFailure;
    }

    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVA;
    }

    @Override
    public boolean accepts(Path file) {
      return file.toString().endsWith(".java");
    }

    @Override
    public void prepare() {
      prepares.incrementAndGet();
      if (prepareFailure != null) {
        throw prepareFailure;
      }
    }

    @Override
    public Optional<Path> parse(Path file) {
      return Optional.of(file);
    }

    @Override
    public SourceFileDefinitions collectDefinitions(Path parsed) {
      return SourceFileDefinitions.empty();
    }

    @Override
    public boolean write(GraphWriter writer, Path file, Path parsed) {
      writer.upsertFile(file, language());
      writer.upsertPackage("prep.test", language());
      return true;
    }

    private int prepares() {
      return prepares.get();
    }
  }

  /**
   * Dynamic fallback adapter that cannot detect a deleted file by reading its contents.
   *
   * @author Oleksii Usatov
   */
  private static final class ContentDetectingRubyAdapter implements LanguageAdapter<Path> {

    private final Path sourceRoot;

    private ContentDetectingRubyAdapter(Path sourceRoot) {
      this.sourceRoot = sourceRoot;
    }

    @Override
    public SourceLanguage language() {
      return RUBY;
    }

    @Override
    public Optional<SourceLanguage> staticLanguage() {
      return Optional.empty();
    }

    @Override
    public boolean accepts(Path file) {
      return file.toString().endsWith(".rb") && Files.exists(sourceRoot.resolve(file));
    }

    @Override
    public boolean acceptsDeletedPath(Path file) {
      return file.toString().endsWith(".rb");
    }

    @Override
    public Optional<Path> parse(Path file) {
      return Files.exists(file) ? Optional.of(file) : Optional.empty();
    }

    @Override
    public SourceFileDefinitions collectDefinitions(Path parsed) {
      return SourceFileDefinitions.of(
          List.of(RUBY_SERVICE_FQN), List.of(), List.of(), List.of(), List.of());
    }

    @Override
    public boolean write(GraphWriter writer, Path file, Path parsed) {
      CtagsGraphWriter ctagsWriter = new CtagsGraphWriter(writer.dependencies());
      writer.upsertProject(sourceRoot, List.of(language()));
      writer.upsertFile(file, language());
      writer.upsertPackage("ruby.test", language());
      ctagsWriter.upsertModule(
          file, language(), "ruby.test", "ruby.test.service", "service", "service.rb", 1, 2);
      ctagsWriter.upsertType(
          file,
          language(),
          "ruby.test",
          RUBY_SERVICE_FQN,
          "Service",
          Params.CLASS,
          Params.CLASS,
          false,
          1,
          2);
      return true;
    }
  }

  /**
   * Dynamic fallback adapter that stops accepting a file after its content changes.
   *
   * @author Oleksii Usatov
   */
  private static final class ContentSwitchingRubyAdapter implements LanguageAdapter<Path> {

    private final Path sourceRoot;

    private ContentSwitchingRubyAdapter(Path sourceRoot) {
      this.sourceRoot = sourceRoot;
    }

    @Override
    public SourceLanguage language() {
      return RUBY;
    }

    @Override
    public Optional<SourceLanguage> staticLanguage() {
      return Optional.empty();
    }

    @Override
    public boolean accepts(Path file) {
      if (!"service".equals(file.getFileName().toString())) {
        return false;
      }
      try {
        return Files.readString(sourceRoot.resolve(file)).startsWith("ruby");
      } catch (IOException _) {
        return false;
      }
    }

    @Override
    public boolean acceptsDeletedPath(Path file) {
      return "service".equals(file.getFileName().toString());
    }

    @Override
    public Optional<Path> parse(Path file) {
      return accepts(LanguageAdapter.localPath(sourceRoot, file))
          ? Optional.of(file)
          : Optional.empty();
    }

    @Override
    public SourceFileDefinitions collectDefinitions(Path parsed) {
      return SourceFileDefinitions.of(
          List.of(RUBY_SERVICE_FQN), List.of(), List.of(), List.of(), List.of());
    }

    @Override
    public boolean write(GraphWriter writer, Path file, Path parsed) {
      CtagsGraphWriter ctagsWriter = new CtagsGraphWriter(writer.dependencies());
      writer.upsertProject(sourceRoot, List.of(language()));
      writer.upsertFile(file, language());
      writer.upsertPackage("ruby.test", language());
      ctagsWriter.upsertModule(
          file, language(), "ruby.test", "ruby.test.service", "service", "service", 1, 2);
      ctagsWriter.upsertType(
          file,
          language(),
          "ruby.test",
          RUBY_SERVICE_FQN,
          "Service",
          Params.CLASS,
          Params.CLASS,
          false,
          1,
          2);
      return true;
    }
  }

  /**
   * Stub adapter whose module identity depends on the configured source root.
   *
   * @author Oleksii Usatov
   */
  private static final class RootAwareStubLanguageAdapter implements LanguageAdapter<Path> {

    private final Path sourceRoot;

    private RootAwareStubLanguageAdapter(Path sourceRoot) {
      this.sourceRoot = sourceRoot;
    }

    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVASCRIPT;
    }

    @Override
    public boolean accepts(Path file) {
      return file.toString().endsWith(".ts");
    }

    @Override
    public LanguageAdapter<Path> forSourceRoot(Path sourceRoot) {
      return new RootAwareStubLanguageAdapter(sourceRoot);
    }

    @Override
    public Optional<Path> parse(Path file) {
      return Optional.of(file);
    }

    @Override
    public SourceFileDefinitions collectDefinitions(Path parsed) {
      String modulePath = sourceRoot.relativize(parsed).toString().replace('\\', '/');
      String moduleName = modulePath.replaceAll("[^A-Za-z0-9]", "_");
      String moduleFqn = "js.test." + moduleName;
      return SourceFileDefinitions.of(
          List.of(moduleFqn), List.of(), List.of(), List.of(moduleFqn + ".<init>()"), List.of());
    }

    @Override
    public boolean write(GraphWriter writer, Path file, Path parsed) {
      JsGraphWriter jsWriter = new JsGraphWriter(writer.dependencies());
      String modulePath = sourceRoot.relativize(file).toString().replace('\\', '/');
      String moduleName = modulePath.replaceAll("[^A-Za-z0-9]", "_");
      String moduleFqn = "js.test." + moduleName;
      writer.upsertFile(file, language());
      writer.upsertPackage("js.test", language());
      jsWriter.upsertModule(file, "js.test", moduleFqn, moduleName, modulePath, 1, 1);
      return true;
    }
  }

  /**
   * Adapter that fails watch source snapshot discovery after an optional priming call.
   *
   * @author Oleksii Usatov
   */
  private static final class SnapshotFailingWatchAdapter implements LanguageAdapter<Object> {

    private final LanguageAdapter<?> delegate;
    private final AtomicInteger discoveries = new AtomicInteger();

    private SnapshotFailingWatchAdapter() {
      this(null);
    }

    private SnapshotFailingWatchAdapter(LanguageAdapter<?> delegate) {
      this.delegate = delegate;
    }

    @Override
    public SourceLanguage language() {
      return delegate == null ? SourceLanguage.JAVA : delegate.language();
    }

    @Override
    public Optional<SourceLanguage> staticLanguage() {
      return delegate == null ? Optional.of(SourceLanguage.JAVA) : delegate.staticLanguage();
    }

    @Override
    public boolean accepts(Path file) {
      return delegate == null ? file.toString().endsWith(".java") : delegate.accepts(file);
    }

    @Override
    public boolean acceptsDeletedPath(Path file) {
      return delegate == null
          ? file.toString().endsWith(".java")
          : delegate.acceptsDeletedPath(file);
    }

    @Override
    public boolean usesCustomFileDiscovery() {
      return true;
    }

    @Override
    public List<Path> discoverFiles(Path sourceRoot) {
      if (discoveries.incrementAndGet() == 1) {
        return List.of();
      }
      throw new ProcessingException("snapshot failed");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Object> parse(Path file) {
      if (delegate != null) {
        return (Optional<Object>) delegate.parse(file);
      }
      return Optional.of(file);
    }

    @SuppressWarnings("unchecked")
    @Override
    public SourceFileDefinitions collectDefinitions(Object parsed) {
      if (delegate != null) {
        return ((LanguageAdapter<Object>) delegate).collectDefinitions(parsed);
      }
      return SourceFileDefinitions.empty();
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean write(GraphWriter writer, Path file, Object parsed) {
      if (delegate != null) {
        return ((LanguageAdapter<Object>) delegate).write(writer, file, parsed);
      }
      writer.upsertFile(file, language());
      return true;
    }

    private int discoveries() {
      return discoveries.get();
    }
  }

  /**
   * Adapter that simulates a file-level failure after stale cleanup has run.
   *
   * @author Oleksii Usatov
   */
  private static final class CleanupThenFailingJavaAdapter implements LanguageAdapter<Object> {

    private final SourceFileDefinitions definitions;

    private CleanupThenFailingJavaAdapter() {
      this(SourceFileDefinitions.of(Set.of(), Set.of(), Set.of(), Set.of(), Set.of()));
    }

    private CleanupThenFailingJavaAdapter(SourceFileDefinitions definitions) {
      this.definitions = definitions;
    }

    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVA;
    }

    @Override
    public boolean accepts(Path file) {
      return file.toString().endsWith(".java");
    }

    @Override
    public Optional<Object> parse(Path file) {
      return Optional.of(file);
    }

    @Override
    public SourceFileDefinitions collectDefinitions(Object parsed) {
      return definitions;
    }

    @Override
    public boolean write(GraphWriter writer, Path file, Object parsed) {
      return false;
    }
  }

  /**
   * Adapter that simulates parse failure before stale cleanup can begin.
   *
   * @author Oleksii Usatov
   */
  private static final class ParseFailingJavaAdapter implements LanguageAdapter<Object> {

    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVA;
    }

    @Override
    public boolean accepts(Path file) {
      return file.toString().endsWith(".java");
    }

    @Override
    public Optional<Object> parse(Path file) {
      return Optional.empty();
    }

    @Override
    public SourceFileDefinitions collectDefinitions(Object parsed) {
      throw new AssertionError("Definition collection must not run after parse failure");
    }

    @Override
    public boolean write(GraphWriter writer, Path file, Object parsed) {
      throw new AssertionError("Graph writes must not run after parse failure");
    }
  }

  /**
   * Adapter that simulates a JS/TS file-level failure after stale cleanup has run.
   *
   * @author Oleksii Usatov
   */
  private static final class CleanupThenFailingJsAdapter implements LanguageAdapter<Object> {

    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVASCRIPT;
    }

    @Override
    public boolean accepts(Path file) {
      return file.toString().endsWith(".ts");
    }

    @Override
    public Optional<Object> parse(Path file) {
      return Optional.of(file);
    }

    @Override
    public SourceFileDefinitions collectDefinitions(Object parsed) {
      return SourceFileDefinitions.of(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Override
    public boolean write(GraphWriter writer, Path file, Object parsed) {
      return false;
    }
  }

  /**
   * Adapter that records whether independent files still use parallel ingest.
   *
   * @author Oleksii Usatov
   */
  private static final class ConcurrencyTrackingJavaAdapter implements LanguageAdapter<Object> {

    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxActive = new AtomicInteger();
    private final CountDownLatch firstWorkers = new CountDownLatch(2);

    @Override
    public SourceLanguage language() {
      return SourceLanguage.JAVA;
    }

    @Override
    public boolean accepts(Path file) {
      return file.toString().endsWith(".java");
    }

    @Override
    public Optional<Object> parse(Path file) {
      int current = active.incrementAndGet();
      maxActive.accumulateAndGet(current, Math::max);
      try {
        firstWorkers.countDown();
        if (current <= 2) {
          await().until(() -> firstWorkers.getCount() == 0);
        }
        return Optional.of(file);
      } finally {
        active.decrementAndGet();
      }
    }

    @Override
    public SourceFileDefinitions collectDefinitions(Object parsed) {
      return SourceFileDefinitions.empty();
    }

    @Override
    public boolean write(GraphWriter writer, Path file, Object parsed) {
      writer.upsertFile(file, language());
      writer.upsertPackage("com.example", language());
      return true;
    }

    private int maxActive() {
      return maxActive.get();
    }
  }

  private static void deleteDir(Path dir) throws IOException {
    if (dir != null && Files.exists(dir)) {
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  var _ = p.toFile().delete();
                });
      }
    }
  }

  @AfterEach
  void cleanup() throws IOException {
    if (currentProject != null) {
      wipeProjectCode(currentProject);
    }
    if (sourceDir != null && Files.exists(sourceDir)) {
      try (Stream<Path> walk = Files.walk(sourceDir)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  var _ = p.toFile().delete();
                });
      }
    }
  }

  @Test
  void ingestsMatchingFilesThroughLanguageGroups() throws Exception {
    currentProject = PROJECT_BASE + "-languages";
    sourceDir = Files.createTempDirectory("orch-languages-src-");
    Files.writeString(
        sourceDir.resolve("Good.java"), "public class Good { int ok() { return 1; } }");
    Path tsFile = Files.writeString(sourceDir.resolve("app.ts"), "export const app = 1;");

    int failures =
        new IngestionOrchestrator(
                sourceDir,
                currentProject,
                1,
                driver,
                List.of(
                    new JavaLanguageAdapter(new ParseService(sourceDir)),
                    new StubJsLanguageAdapter()))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long javaFiles =
          s.run(
                  "MATCH (:Project {name: $p})-[:CONTAINS]->(:Language {name: 'Java'})"
                      + "-[:CONTAINS]->(:Code {language: 'java'})-[:CONTAINS]->"
                      + "(:File {path: $path, project: $p}) RETURN count(*) AS n",
                  Map.of("p", currentProject, "path", sourceDir.resolve("Good.java").toString()))
              .single()
              .get("n")
              .asLong();
      long jsFiles =
          s.run(
                  "MATCH (:Project {name: $p})-[:CONTAINS]->(:Language {name: 'Js'})"
                      + "-[:CONTAINS]->(:Code {language: 'js'})-[:CONTAINS]->"
                      + "(:File {path: $path, project: $p}) RETURN count(*) AS n",
                  Map.of("p", currentProject, "path", tsFile.toString()))
              .single()
              .get("n")
              .asLong();

      assertEquals(1, javaFiles);
      assertEquals(1, jsFiles);
    }
  }

  @Test
  void reingestionAndWatchRefreshCodeChunksFromJavaDocumentation() throws Exception {
    sourceDir = Files.createTempDirectory("orch-rag-src-");
    currentProject = PROJECT_BASE + "-rag-chunks";
    Path sourceFile = sourceDir.resolve("com/example/Widget.java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(
        sourceFile,
        """
        package com.example;

        /** Original searchable widget docs. */
        public class Widget {
          /** Original method contract. */
          public String name() {
            return "old";
          }
        }
        """);

    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    assertEquals(0, orchestrator.run(Settings.def()));

    List<String> initialChunks = codeChunkTexts(currentProject, sourceFile);
    assertTrue(
        initialChunks.stream().anyMatch(text -> text.contains("Original searchable widget docs")),
        () ->
            "initial chunks: " + initialChunks + ", all: " + allCodeChunkSummaries(currentProject));
    assertTrue(
        initialChunks.stream().anyMatch(text -> text.contains("Original method contract")),
        () -> "initial chunks: " + initialChunks);

    Files.writeString(
        sourceFile,
        """
        package com.example;

        /** Updated searchable widget docs. */
        public class Widget {
          /** Updated method contract. */
          public String name() {
            return "new";
          }
        }
        """);

    orchestrator.ingestChangedFiles(Set.of(sourceFile));

    List<String> refreshedChunks = codeChunkTexts(currentProject, sourceFile);
    assertTrue(
        refreshedChunks.stream().anyMatch(text -> text.contains("Updated searchable widget docs")),
        () -> "refreshed chunks: " + refreshedChunks);
    assertTrue(
        refreshedChunks.stream().anyMatch(text -> text.contains("Updated method contract")),
        () -> "refreshed chunks: " + refreshedChunks);
    assertFalse(
        refreshedChunks.stream()
            .anyMatch(text -> text.contains("Original searchable widget docs")));

    Files.delete(sourceFile);

    orchestrator.ingestChangedFiles(Set.of(sourceFile));

    assertEquals(0, codeChunkCount(currentProject, sourceFile));
  }

  @Test
  void sharedDiscoveryUsesAdapterSpecificDirectoryPruning() throws Exception {
    currentProject = PROJECT_BASE + "-shared-pruning";
    sourceDir = Files.createTempDirectory("orch-shared-pruning-src-");
    Path appFile =
        Files.writeString(
            sourceDir.resolve("Good.java"), "public class Good { int ok() { return 1; } }");
    Path buildJavaFile = sourceDir.resolve("build/BuildSource.java");
    Files.createDirectories(buildJavaFile.getParent());
    Files.writeString(buildJavaFile, "public class BuildSource { int ok() { return 1; } }");
    Path buildPythonFile = buildJavaFile.getParent().resolve("ignored.py");
    Files.writeString(buildPythonFile, "value = 1\n");
    Path targetJavaFile = sourceDir.resolve("target/classes/Generated.java");
    Files.createDirectories(targetJavaFile.getParent());
    Files.writeString(targetJavaFile, "public class Generated { int bad() { return 1; } }");
    Path targetPythonFile = targetJavaFile.getParent().resolve("generated.py");
    Files.writeString(targetPythonFile, "value = 1\n");
    Path venvJavaFile = sourceDir.resolve(".venv/Leak.java");
    Files.createDirectories(venvJavaFile.getParent());
    Files.writeString(venvJavaFile, "public class Leak { int bad() { return 1; } }");

    int failures =
        new IngestionOrchestrator(
                sourceDir,
                currentProject,
                1,
                driver,
                List.of(
                    new JavaLanguageAdapter(new ParseService(sourceDir)),
                    new PythonLanguageAdapter(null)))
            .run(Settings.def());

    assertEquals(0, failures);
    assertTrue(fileExistsInGraph(currentProject, appFile));
    assertTrue(fileExistsInGraph(currentProject, buildJavaFile));
    assertFalse(fileExistsInGraph(currentProject, buildPythonFile));
    assertFalse(fileExistsInGraph(currentProject, targetJavaFile));
    assertFalse(fileExistsInGraph(currentProject, targetPythonFile));
    assertTrue(fileExistsInGraph(currentProject, venvJavaFile));
  }

  @Test
  void ingestsPythonSourceWithClassesFieldsAndCalls() throws Exception {
    assumeTrue(systemPythonAvailable(), "Python ingestion IT requires python3");
    currentProject = PROJECT_BASE + "-python";
    sourceDir = Files.createTempDirectory("orch-python-src-");
    Path appDir = sourceDir.resolve("app");
    Files.createDirectories(appDir);
    Files.writeString(
        appDir.resolve("base.py"),
        """
        class Base:
            pass
        """);
    Files.writeString(
        appDir.resolve("service.py"),
        """
        from .base import Base

        class Service(Base):
            def __init__(self):
                self.name: str = "service"

            def run(self):
                helper()

        def helper():
            return 1
        """);

    int failures =
        new IngestionOrchestrator(
                sourceDir,
                currentProject,
                1,
                driver,
                new PythonLanguageAdapter(
                    new PythonAnalyzer(
                        sourceDir,
                        new ManagedPythonRuntime(
                            sourceDir.resolve(".runtime-cache"),
                            ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
                            ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
                            RuntimeMode.SYSTEM))))
            .run(Settings.applySchemaOnly());

    String baseFqn = "python.app.base$2e$py.Base";
    String serviceFqn = "python.app.service$2e$py.Service";
    assertEquals(0, failures);
    assertTrue(classExists(currentProject, serviceFqn));
    assertTrue(
        methodExists(currentProject, serviceFqn + ".run()"),
        () -> "Method signatures: " + methodSignatures(currentProject));
    assertTrue(fieldExists(currentProject, serviceFqn + "#name"));
    assertTrue(classExtendsEdgeExists(currentProject, serviceFqn, baseFqn));
    assertTrue(
        callEdgeExists(currentProject, serviceFqn + ".run()", "python.app.service$2e$py.helper()"));
  }

  @Test
  void appliesLanguageSchemaMigrationWhenLegacySchemaIsDetected() throws Exception {
    currentProject = PROJECT_BASE + "-legacy-schema";
    sourceDir = Files.createTempDirectory("orch-legacy-schema-src-");
    Files.writeString(
        sourceDir.resolve("Good.java"), "public class Good { int ok() { return 1; } }");
    Path tsFile = Files.writeString(sourceDir.resolve("app.ts"), "export const app = 1;");

    try (Session s = driver.session()) {
      Memgraph.wipeAllData(s);
      s.run("CREATE CONSTRAINT ON (p:Project) ASSERT p.name IS UNIQUE").consume();
      s.run("CREATE CONSTRAINT ON (c:Code) ASSERT c.project IS UNIQUE").consume();
      s.run(
              "CREATE (project:Project {name: $p})"
                  + " CREATE (project)-[:CONTAINS]->(:Code {project: $p})",
              Map.of("p", currentProject))
          .consume();
    }

    int failures =
        new IngestionOrchestrator(
                sourceDir,
                currentProject,
                1,
                driver,
                List.of(
                    new JavaLanguageAdapter(new ParseService(sourceDir)),
                    new StubJsLanguageAdapter()))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long jsFiles =
          s.run(
                  "MATCH (:Project {name: $p})-[:CONTAINS]->(:Language {name: 'Js'})"
                      + "-[:CONTAINS]->(:Code {language: 'js'})-[:CONTAINS]->"
                      + "(:File {path: $path, project: $p}) RETURN count(*) AS n",
                  Map.of("p", currentProject, "path", tsFile.toString()))
              .single()
              .get("n")
              .asLong();

      assertEquals(1, jsFiles);
    }
  }

  @Test
  void ingestsSequentiallyWithZeroFailuresInitSchema() throws Exception {
    currentProject = PROJECT_BASE + "-seq";
    sourceDir = buildSampleSourceTree();

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.applySchemaOnly());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(classCount >= 1, "At least one :Class node expected after ingestion");

      long codeRootCount =
          s.run(
                  "MATCH (:Project {name: $p})-[:CONTAINS]->(:Language {name: 'Java'})"
                      + "-[:CONTAINS]->(:Code {project: $p, language: 'java'})"
                      + "-[:CONTAINS]->(:File)-[:DEFINES]->(:Class)"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(codeRootCount >= 1, "Code graph must hang under :Project -> :Language -> :Code");

      long memoryRootCount =
          s.run(
                  "MATCH (:Project {name: $p})-[:HAS_MEMORY]->(:Memory {project: $p})"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(1, memoryRootCount, "Memory graph must hang under :Project -> :Memory");
    }
  }

  @Test
  void ingestsSequentiallyWithZeroFailuresWipeAll() throws Exception {
    currentProject = PROJECT_BASE + "-seq";
    sourceDir = buildSampleSourceTree();

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.wipeAllAndApplySchema());
    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(classCount >= 1, "At least one :Class node expected after ingestion");
    }
  }

  @Test
  void watchModeStopsWhenInterrupted() throws Exception {
    currentProject = PROJECT_BASE + "-watch";
    sourceDir = Files.createTempDirectory("orch-watch-src-");
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                orchestrator.run(new Settings(false, true, false, false, true));
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "watch-mode-test");
    worker.start();

    await()
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(50))
        .until(() -> worker.getState() == Thread.State.WAITING);
    await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(() -> true);

    Path watchedFile = sourceDir.resolve("Watched.java");
    createClassCodeRef(currentProject, "Watched");

    try {
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(250))
          .untilAsserted(
              () -> {
                Files.writeString(
                    watchedFile,
                    """
                    public class Watched {
                      public int value(String text) {
                        return text.length();
                      }
                    }
                    """);
                assertTrue(
                    classExists(currentProject, "Watched"), "Watch mode must ingest changed files");
                assertTrue(
                    hasNoPhantomMethods(currentProject),
                    "Watch mode must clean up phantom external Method nodes");
                assertTrue(
                    classCodeRefResolved(currentProject, "Watched"),
                    "Watch mode must refresh CodeRef resolution edges");
              });
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(50))
          .until(() -> worker.getState() == Thread.State.WAITING);
      Files.delete(watchedFile);
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(250))
          .untilAsserted(
              () ->
                  assertFalse(
                      classExists(currentProject, "Watched"),
                      "Watch mode must delete graph state for removed files"));
    } finally {
      worker.interrupt();
      worker.join(TimeUnit.SECONDS.toMillis(5));
    }

    assertFalse(worker.isAlive(), "Watch mode must exit after interruption");
    assertNull(failure.get(), () -> "Watch mode failed: " + failure.get());
  }

  @Test
  void watchModeDeletesDynamicFallbackFileAfterFileIsGone() throws Exception {
    currentProject = PROJECT_BASE + "-watch-dynamic-delete";
    sourceDir = Files.createTempDirectory("orch-watch-dynamic-delete-src-");
    Path watchedFile = sourceDir.resolve("service.rb");
    Files.writeString(watchedFile, "class Service\nend\n");
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ContentDetectingRubyAdapter(sourceDir));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                orchestrator.run(new Settings(false, true, false, false, true));
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "watch-dynamic-delete-test");
    worker.start();

    try {
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(
              () -> {
                assertTrue(fileExistsInGraph(currentProject, watchedFile));
                assertTrue(classExists(currentProject, RUBY_SERVICE_FQN));
              });
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(50))
          .until(() -> worker.getState() == Thread.State.WAITING);

      Files.delete(watchedFile);

      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(250))
          .untilAsserted(
              () -> {
                assertFalse(fileExistsInGraph(currentProject, watchedFile));
                assertFalse(classExists(currentProject, RUBY_SERVICE_FQN));
              });
    } finally {
      worker.interrupt();
      worker.join(TimeUnit.SECONDS.toMillis(5));
    }

    assertFalse(worker.isAlive(), "Watch mode must exit after interruption");
    assertNull(failure.get(), () -> "Watch mode failed: " + failure.get());
  }

  @Test
  void watchModeDeletesDynamicFallbackFileAfterItIsNoLongerAccepted() throws Exception {
    currentProject = PROJECT_BASE + "-watch-dynamic-reject";
    sourceDir = Files.createTempDirectory("orch-watch-dynamic-reject-src-");
    Path watchedFile = sourceDir.resolve("service");
    Files.writeString(watchedFile, "ruby service\n");
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ContentSwitchingRubyAdapter(sourceDir));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                orchestrator.run(new Settings(false, true, false, false, true));
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "watch-dynamic-reject-test");
    worker.start();

    try {
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(100))
          .untilAsserted(
              () -> {
                assertTrue(fileExistsInGraph(currentProject, watchedFile));
                assertTrue(classExists(currentProject, RUBY_SERVICE_FQN));
              });
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(50))
          .until(() -> worker.getState() == Thread.State.WAITING);

      Files.writeString(watchedFile, "console.log('now first class');\n");

      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(250))
          .untilAsserted(
              () -> {
                assertFalse(fileExistsInGraph(currentProject, watchedFile));
                assertFalse(classExists(currentProject, RUBY_SERVICE_FQN));
              });
    } finally {
      worker.interrupt();
      worker.join(TimeUnit.SECONDS.toMillis(5));
    }

    assertFalse(worker.isAlive(), "Watch mode must exit after interruption");
    assertNull(failure.get(), () -> "Watch mode failed: " + failure.get());
  }

  @Test
  void reingestionDeletesDynamicFallbackFileAfterItIsNoLongerAccepted() throws Exception {
    currentProject = PROJECT_BASE + "-dynamic-reject";
    sourceDir = Files.createTempDirectory("orch-dynamic-reject-src-");
    Path sourceFile = sourceDir.resolve("service");
    Files.writeString(sourceFile, "ruby service\n");
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ContentSwitchingRubyAdapter(sourceDir));

    assertEquals(0, orchestrator.run(Settings.def()));
    assertTrue(fileExistsInGraph(currentProject, sourceFile));
    assertTrue(classExists(currentProject, RUBY_SERVICE_FQN));

    Files.writeString(sourceFile, "plain text\n");

    assertEquals(0, orchestrator.run(Settings.def()));
    assertFalse(fileExistsInGraph(currentProject, sourceFile));
    assertFalse(classExists(currentProject, RUBY_SERVICE_FQN));
  }

  @Test
  void watchDeleteRetriesTransientFailure() throws Exception {
    currentProject = PROJECT_BASE + "-watch-delete-retry";
    sourceDir = Files.createTempDirectory("orch-watch-delete-retry-src-");
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    AtomicInteger attempts = new AtomicInteger();

    boolean deleted =
        orchestrator.deleteSourceFileWithRetry(
            sourceDir.resolve("Gone.java"),
            _ -> {
              if (attempts.getAndIncrement() == 0) {
                throw new RuntimeException("conflicting transactions");
              }
            });

    assertTrue(deleted);
    assertEquals(2, attempts.get());
  }

  @Test
  void retainedFileLookupRetriesTransientFailure() throws Exception {
    currentProject = PROJECT_BASE + "-retained-lookup-retry";
    sourceDir = Files.createTempDirectory("orch-retained-lookup-retry-src-");
    Path deletedFile = sourceDir.resolve("Gone.java");
    Path retainedFile = sourceDir.resolve("Retained.java");
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    AtomicInteger attempts = new AtomicInteger();

    var retainedFiles =
        orchestrator.retainedFilesSharingDefinitionsWithRetry(
            deletedFile,
            _ -> {
              if (attempts.getAndIncrement() == 0) {
                throw new RuntimeException("deadlock detected");
              }
              return Set.of(retainedFile);
            });

    assertTrue(retainedFiles.isPresent());
    assertEquals(Set.of(retainedFile), retainedFiles.get());
    assertEquals(2, attempts.get());
  }

  @Test
  @SuppressWarnings("java:S2699")
  void retainedRefreshCatchesSourceRootLookupFailure() throws Exception {
    currentProject = PROJECT_BASE + "-retained-refresh-lookup-failure";
    sourceDir = Files.createTempDirectory("orch-retained-refresh-lookup-failure-src-");
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    Session closedSession = driver.session();
    IngestionRunStats stats = new IngestionRunStats(1);
    GraphWriter writer = new GraphWriter(closedSession, currentProject, stats);
    closedSession.close();

    orchestrator.refreshRetainedFilesAfterDelete(
        writer, Set.of(sourceDir.resolve("Retained.java")), Map.of(), stats);
  }

  @Test
  void missingFileCleanupRetriesTransientFailure() throws Exception {
    currentProject = PROJECT_BASE + "-missing-cleanup-retry";
    sourceDir = Files.createTempDirectory("orch-missing-cleanup-retry-src-");
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    AtomicInteger attempts = new AtomicInteger();

    orchestrator.runMissingFileCleanupWithRetry(
        sourceDir,
        SourceLanguage.JAVA,
        () -> {
          if (attempts.getAndIncrement() == 0) {
            throw new RuntimeException("deadlock detected");
          }
        });

    assertEquals(2, attempts.get());
  }

  @Test
  void watchModeSkipsCycleWhenSourceSnapshotFails() throws Exception {
    currentProject = PROJECT_BASE + "-watch-snapshot-failure";
    sourceDir = Files.createTempDirectory("orch-watch-snapshot-failure-src-");
    SnapshotFailingWatchAdapter adapter = new SnapshotFailingWatchAdapter();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, adapter);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                orchestrator.run(new Settings(false, true, false, false, true));
              } catch (Throwable t) {
                failure.set(t);
              }
            },
            "watch-snapshot-failure-test");
    worker.start();

    try {
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(50))
          .until(() -> worker.getState() == Thread.State.WAITING);
      await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(() -> true);
      Path flakyFile = sourceDir.resolve("Flaky.java");
      await()
          .atMost(Duration.ofSeconds(10))
          .pollInterval(Duration.ofMillis(250))
          .untilAsserted(
              () -> {
                Files.writeString(
                    flakyFile, "public class Flaky { int value = " + adapter.discoveries() + "; }");
                assertTrue(adapter.discoveries() >= 2);
              });
      await()
          .atMost(Duration.ofSeconds(5))
          .pollInterval(Duration.ofMillis(50))
          .until(() -> worker.isAlive() && failure.get() == null);
    } finally {
      worker.interrupt();
      worker.join(TimeUnit.SECONDS.toMillis(5));
    }

    assertFalse(worker.isAlive(), "Watch mode must exit after interruption");
    assertNull(failure.get(), () -> "Watch mode failed: " + failure.get());
  }

  @Test
  void watchModeReconcilesDeletedFilesWhenSourceSnapshotFails() throws Exception {
    currentProject = PROJECT_BASE + "-watch-snapshot-failure-delete";
    sourceDir = Files.createTempDirectory("orch-watch-snapshot-failure-delete-src-");
    Path deletedFile = sourceDir.resolve("Deleted.java");
    try (Session s = driver.session()) {
      s.run(
              """
              MERGE (f:File {path: $path, project: $p})
              SET f.language = 'java'
              MERGE (c:Class {fqn: 'Deleted', project: $p})
              SET c.name = 'Deleted', c.language = 'java', c.isExternal = false
              MERGE (f)-[:DEFINES]->(c)
              """,
              Map.of("p", currentProject, "path", deletedFile.toString()))
          .consume();
    }
    SnapshotFailingWatchAdapter adapter = new SnapshotFailingWatchAdapter();
    adapter.discoverFiles(sourceDir);
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, adapter);

    orchestrator.ingestChangedFiles(Set.of(deletedFile));

    assertFalse(fileExistsInGraph(currentProject, deletedFile));
    assertFalse(classExists(currentProject, "Deleted"));
  }

  @Test
  void watchModeReconcilesDeletedFallbackFilesWhenSourceSnapshotFails() throws Exception {
    currentProject = PROJECT_BASE + "-watch-snapshot-failure-fallback-delete";
    sourceDir = Files.createTempDirectory("orch-watch-snapshot-failure-fallback-delete-src-");
    Path deletedFile = sourceDir.resolve("service.rb");
    Files.writeString(deletedFile, "class Service\nend\n");
    LanguageAdapter<?> delegate = new ContentDetectingRubyAdapter(sourceDir);
    IngestionOrchestrator initial =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, delegate);
    assertEquals(0, initial.run(Settings.def()));
    assertTrue(fileExistsInGraph(currentProject, deletedFile));
    assertTrue(classExists(currentProject, RUBY_SERVICE_FQN));
    Files.delete(deletedFile);

    SnapshotFailingWatchAdapter adapter = new SnapshotFailingWatchAdapter(delegate);
    adapter.discoverFiles(sourceDir);
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, adapter);

    orchestrator.ingestChangedFiles(Set.of(deletedFile));

    assertFalse(fileExistsInGraph(currentProject, deletedFile));
    assertFalse(classExists(currentProject, RUBY_SERVICE_FQN));
  }

  @Test
  void watchModeKeepsDeletedFilesWhenSnapshotFailsWithExistingChanges() throws Exception {
    currentProject = PROJECT_BASE + "-watch-snapshot-failure-mixed";
    sourceDir = Files.createTempDirectory("orch-watch-snapshot-failure-mixed-src-");
    Path deletedFile = sourceDir.resolve("OldShared.java");
    Path existingFile = sourceDir.resolve("NewShared.java");
    Files.writeString(existingFile, "class Shared { int value; }");
    try (Session s = driver.session()) {
      s.run(
              """
              MERGE (f:File {path: $path, project: $p})
              SET f.language = 'java'
              MERGE (c:Class {fqn: 'Shared', project: $p})
              SET c.name = 'Shared', c.language = 'java', c.isExternal = false
              MERGE (f)-[:DEFINES]->(c)
              """,
              Map.of("p", currentProject, "path", deletedFile.toString()))
          .consume();
    }
    SnapshotFailingWatchAdapter adapter = new SnapshotFailingWatchAdapter();
    adapter.discoverFiles(sourceDir);
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, adapter);

    orchestrator.ingestChangedFiles(Set.of(deletedFile, existingFile));

    assertTrue(fileExistsInGraph(currentProject, deletedFile));
    assertFalse(fileExistsInGraph(currentProject, existingFile));
  }

  @Test
  void watchModeRefreshesRetainedFileAfterFallbackDelete() throws Exception {
    currentProject = PROJECT_BASE + "-watch-snapshot-fallback-refresh";
    sourceDir = Files.createTempDirectory("orch-watch-snapshot-fallback-refresh-src-");
    Path pkg = sourceDir.resolve("com/example");
    Files.createDirectories(pkg);
    Path oldShared = pkg.resolve("OldShared.java");
    Path newShared = pkg.resolve("NewShared.java");
    Files.writeString(pkg.resolve("Helper.java"), helperSource());
    Files.writeString(oldShared, sharedWithHelperCallSource());
    IngestionOrchestrator normal =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, normal.run(Settings.def()));
    assertTrue(
        callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));

    Files.writeString(newShared, sharedWithoutHelperCallSource());
    normal.ingestChangedFiles(Set.of(newShared));
    assertTrue(
        callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));

    Files.delete(oldShared);
    SnapshotFailingWatchAdapter adapter =
        new SnapshotFailingWatchAdapter(new JavaLanguageAdapter(new ParseService(sourceDir)));
    adapter.discoverFiles(sourceDir);
    IngestionOrchestrator failingSnapshot =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, adapter);

    failingSnapshot.ingestChangedFiles(Set.of(oldShared));

    assertTrue(fileExistsInGraph(currentProject, newShared));
    assertFalse(
        callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
  }

  @Test
  void watchModeSkipsDeletedFilesWhenFileUpdateFails() throws Exception {
    currentProject = PROJECT_BASE + "-watch-failed-update-skip-delete";
    sourceDir = Files.createTempDirectory("orch-watch-failed-update-skip-delete-src-");
    Path existingFile = sourceDir.resolve("Existing.java");
    Path deletedFile = sourceDir.resolve("Deleted.java");
    Files.writeString(existingFile, "class Existing {}");
    try (Session s = driver.session()) {
      s.run(
              """
              MERGE (f:File {path: $path, project: $p})
              SET f.language = 'java'
              MERGE (c:Class {fqn: 'Deleted', project: $p})
              SET c.name = 'Deleted', c.language = 'java', c.isExternal = false
              MERGE (f)-[:DEFINES]->(c)
              """,
              Map.of("p", currentProject, "path", deletedFile.toString()))
          .consume();
    }
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new CleanupThenFailingJavaAdapter());

    orchestrator.ingestChangedFiles(Set.of(existingFile, deletedFile));

    assertTrue(fileExistsInGraph(currentProject, deletedFile));
  }

  @Test
  void watchModeRefreshesRetainedFileAfterDeletingMovedSharedMethod() throws Exception {
    currentProject = PROJECT_BASE + "-watch-delete-moved-shared-method";
    sourceDir = Files.createTempDirectory("orch-watch-delete-moved-shared-method-src-");
    Path pkg = sourceDir.resolve("com/example");
    Files.createDirectories(pkg);
    Path helper = pkg.resolve("Helper.java");
    Path oldShared = pkg.resolve("OldShared.java");
    Path newShared = pkg.resolve("NewShared.java");
    Files.writeString(
        helper,
        """
        package com.example;

        class Helper {
          static void go() {}
        }
        """);
    Files.writeString(
        oldShared,
        """
        package com.example;

        class Shared {
          void serve() {
            Helper.go();
          }
        }
        """);
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, orchestrator.run(Settings.def()));
    assertTrue(
        callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));

    Files.writeString(
        newShared,
        """
        package com.example;

        class Shared {
          void serve() {}
        }
        """);
    orchestrator.ingestChangedFiles(Set.of(newShared));
    assertTrue(
        callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));

    Files.delete(oldShared);
    orchestrator.ingestChangedFiles(Set.of(oldShared));

    assertTrue(fileExistsInGraph(currentProject, newShared));
    assertFalse(
        callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
  }

  @Test
  void watchModeRefreshesRetainedFileFromOtherSourceRootAfterDelete() throws Exception {
    currentProject = PROJECT_BASE + "-watch-delete-moved-shared-other-root";
    sourceDir = Files.createTempDirectory("orch-watch-delete-moved-shared-root-a-");
    Path otherRoot = Files.createTempDirectory("orch-watch-delete-moved-shared-root-b-");
    try {
      Path rootA = sourceDir.resolve("com/example");
      Path rootB = otherRoot.resolve("com/example");
      Files.createDirectories(rootA);
      Files.createDirectories(rootB);
      Path oldShared = rootA.resolve("OldShared.java");
      Path newShared = rootB.resolve("NewShared.java");
      Files.writeString(rootA.resolve("Helper.java"), helperSource());
      Files.writeString(rootB.resolve("Helper.java"), helperSource());
      Files.writeString(oldShared, sharedWithHelperCallSource());
      Files.writeString(newShared, sharedWithoutHelperCallSource());
      IngestionOrchestrator rootAOrchestrator =
          new IngestionOrchestrator(
              sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
      IngestionOrchestrator rootBOrchestrator =
          new IngestionOrchestrator(
              otherRoot, currentProject, 1, driver, new ParseService(otherRoot));
      assertEquals(0, rootAOrchestrator.run(Settings.def()));
      assertTrue(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
      assertEquals(0, rootBOrchestrator.run(Settings.def()));
      assertTrue(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));

      Files.delete(oldShared);
      rootAOrchestrator.ingestChangedFiles(Set.of(oldShared));

      assertTrue(fileExistsInGraph(currentProject, newShared));
      assertFalse(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
    } finally {
      deleteDir(otherRoot);
    }
  }

  @Test
  void watchModeRefreshesRetainedModuleFileWithStoredSourceRoot() throws Exception {
    currentProject = PROJECT_BASE + "-watch-delete-module-retained-root";
    sourceDir = Files.createTempDirectory("orch-watch-delete-module-root-a-");
    Path otherRoot = Files.createTempDirectory("orch-watch-delete-module-root-b-");
    try {
      Path removedFile = sourceDir.resolve("shared.ts");
      Path retainedFile = otherRoot.resolve("shared.ts");
      Files.writeString(removedFile, "export const shared = true;\n");
      Files.writeString(retainedFile, "export const shared = true;\n");
      IngestionOrchestrator rootAOrchestrator =
          new IngestionOrchestrator(
              sourceDir, currentProject, 1, driver, new RootAwareStubLanguageAdapter(sourceDir));
      IngestionOrchestrator rootBOrchestrator =
          new IngestionOrchestrator(
              otherRoot, currentProject, 1, driver, new RootAwareStubLanguageAdapter(otherRoot));
      assertEquals(0, rootAOrchestrator.run(Settings.def()));
      assertEquals(0, rootBOrchestrator.run(Settings.def()));
      assertEquals(List.of("js.test.shared_ts"), moduleFqnsForFile(currentProject, retainedFile));

      Files.delete(removedFile);
      rootAOrchestrator.ingestChangedFiles(Set.of(removedFile));

      assertEquals(List.of("js.test.shared_ts"), moduleFqnsForFile(currentProject, retainedFile));
    } finally {
      deleteDir(otherRoot);
    }
  }

  @Test
  void ingestsSequentiallyWithZeroFailures() throws Exception {
    currentProject = PROJECT_BASE + "-seq";
    sourceDir = buildSampleSourceTree();

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(classCount >= 1, "At least one :Class node expected after ingestion");
    }
  }

  @Test
  void ingestsInParallelWithZeroFailures() throws Exception {
    currentProject = PROJECT_BASE + "-par";
    sourceDir = buildSampleSourceTree();

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 4, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(classCount >= 1);
    }
  }

  @Test
  void ingestsRepeatedAnnotationsInParallelWithZeroFailures() throws Exception {
    currentProject = PROJECT_BASE + "-ann-par";
    sourceDir = Files.createTempDirectory("orch-ann-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    for (int i = 0; i < 40; i++) {
      Files.writeString(
          pkgDir.resolve("Annotated" + i + ".java"),
          """
          package com.example;

          @Deprecated
          public class Annotated%s {}
          """
              .formatted(i));
    }

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 8, driver, new ParseService(sourceDir))
            .run(Settings.wipeAllAndApplySchema());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long annotationCount =
          s.run(
                  "MATCH (a:Annotation {project: $p, fqn: 'java.lang.Deprecated'})"
                      + " RETURN count(a) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(1, annotationCount, "Repeated annotations must converge to one node");
    }
  }

  @Test
  void ingestsEnumRecordAndAnnotationTopLevelTypes() throws Exception {
    currentProject = PROJECT_BASE + "-mixed-types";
    sourceDir = Files.createTempDirectory("orch-mixed-types-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("AllTypes.java"),
        """
        package com.example;

        @interface Marker {}

        enum Status {
          ACTIVE
        }

        record Point(int x) {}

        class Holder {
          @Marker void marked() {}
          Status status;
          Point point;
        }
        """);

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long enumCount =
          s.run(
                  "MATCH (c:Class {project: $p, fqn: 'com.example.Status', isEnum: true})"
                      + " RETURN count(c) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      long recordCount =
          s.run(
                  "MATCH (c:Class {project: $p, fqn: 'com.example.Point', isRecord: true})"
                      + " RETURN count(c) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      long annotationCount =
          s.run(
                  "MATCH (a:Annotation {project: $p, fqn: 'com.example.Marker'})"
                      + " RETURN count(a) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();

      assertEquals(1, enumCount);
      assertEquals(1, recordCount);
      assertEquals(1, annotationCount);

      List<String> recordChunkSources =
          s.run(
                  "MATCH (chunk:CodeChunk {project: $p, path: $path})"
                      + " WHERE chunk.sourceId STARTS WITH 'com.example.Point'"
                      + " RETURN chunk.sourceLabel + ':' + chunk.sourceId AS source"
                      + " ORDER BY source",
                  Map.of("p", currentProject, "path", pkgDir.resolve("AllTypes.java").toString()))
              .list(r -> r.get("source").asString());
      assertTrue(recordChunkSources.contains("Field:com.example.Point#x"));
      assertTrue(recordChunkSources.contains("Method:com.example.Point.<init>(int)"));
      assertTrue(recordChunkSources.contains("Method:com.example.Point.x()"));
    }
  }

  @Test
  void parallelAndSequentialIngestSameNodeCount() throws Exception {
    Path seqDir = buildSampleSourceTree();
    Path parDir = buildSampleSourceTree();
    String seqProject = PROJECT_BASE + "-cmp-seq";
    String parProject = PROJECT_BASE + "-cmp-par";

    try {
      new IngestionOrchestrator(seqDir, seqProject, 1, driver, new ParseService(seqDir))
          .run(Settings.def());
      new IngestionOrchestrator(parDir, parProject, 4, driver, new ParseService(parDir))
          .run(Settings.def());

      try (Session s = driver.session()) {
        long seqClasses =
            s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", seqProject))
                .single()
                .get("n")
                .asLong();
        long parClasses =
            s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", parProject))
                .single()
                .get("n")
                .asLong();
        assertEquals(
            seqClasses, parClasses, "Sequential and parallel must produce equal class count");
      }
    } finally {
      wipeProjectCode(seqProject);
      wipeProjectCode(parProject);
      deleteDir(seqDir);
      deleteDir(parDir);
    }
  }

  @Test
  void newFilesWithoutDefinitionOverlapStillIngestInParallel() throws Exception {
    currentProject = PROJECT_BASE + "-new-files-parallel";
    sourceDir = Files.createTempDirectory("orch-new-files-parallel-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Seed.java"),
        """
        package com.example;

        public class Seed {}
        """);
    IngestionOrchestrator normal =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, normal.run(Settings.def()));

    Files.writeString(
        pkgDir.resolve("FreshOne.java"),
        """
        package com.example;

        public class FreshOne {}
        """);
    Files.writeString(
        pkgDir.resolve("FreshTwo.java"),
        """
        package com.example;

        public class FreshTwo {}
        """);
    ConcurrencyTrackingJavaAdapter adapter = new ConcurrencyTrackingJavaAdapter();
    IngestionOrchestrator parallel =
        new IngestionOrchestrator(sourceDir, currentProject, 2, driver, adapter);

    assertEquals(0, parallel.run(Settings.def()));

    assertTrue(adapter.maxActive() > 1, "independent new files should use parallel ingest");
  }

  @Test
  void wipeDeletesExistingNodesBeforeReingest() throws Exception {
    currentProject = PROJECT_BASE + "-wipe";
    sourceDir = buildSampleSourceTree();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    orchestrator.run(Settings.def());
    long countAfterFirst;
    try (Session s = driver.session()) {
      countAfterFirst =
          s.run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
    }

    int failures = orchestrator.run(Settings.wipeProjCodeOnly());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long countAfterWipe =
          s.run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(countAfterFirst, countAfterWipe, "Wipe + reingest must yield same node count");
    }
  }

  @Test
  void wipeAllMemoriesDeletesMemoryGraphBeforeReingest() throws Exception {
    currentProject = PROJECT_BASE + "-wipe-mem";
    sourceDir = buildSampleSourceTree();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    orchestrator.run(Settings.def());
    try (Session s = driver.session()) {
      s.run(
              "MATCH (m:Memory {project: $p})"
                  + " MERGE (d:Decision {id: 'DEC-test-orchestrator-wipe-memories', project: $p})"
                  + " SET d.status = 'accepted'"
                  + " MERGE (m)-[:HAS_DECISION]->(d)",
              Map.of("p", currentProject))
          .consume();
    }

    int failures = orchestrator.run(new Settings(false, false, false, true, false));

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long decisionCount =
          s.run(
                  "MATCH (d:Decision {id: 'DEC-test-orchestrator-wipe-memories', project: $p})"
                      + " RETURN count(d) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(0, decisionCount, "Memory wipe must delete existing decisions");

      long memoryRootCount =
          s.run(
                  "MATCH (:Project {name: $p})-[:HAS_MEMORY]->(:Memory {project: $p})"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(1, memoryRootCount, "Reingest must recreate an empty Memory root");

      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(classCount >= 1, "Code graph must still be present after memory-only wipe");
    }
  }

  @Test
  void reingestionWithoutWipeIsIdempotent() throws Exception {
    currentProject = PROJECT_BASE + "-idem";
    sourceDir = buildSampleSourceTree();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    orchestrator.run(Settings.def());
    long countAfterFirst;
    try (Session s = driver.session()) {
      countAfterFirst =
          s.run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
    }

    orchestrator.run(Settings.def());

    try (Session s = driver.session()) {
      long countAfterSecond =
          s.run("MATCH (n) WHERE n.project = $p RETURN count(n) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(countAfterFirst, countAfterSecond, "MERGE-based upsert must be idempotent");
    }
  }

  @Test
  void reingestionDeletesRemovedSourceFiles() throws Exception {
    currentProject = PROJECT_BASE + "-removed-file";
    sourceDir = buildSampleSourceTree();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    Path widgetFile = sourceDir.resolve("com/example/Widget.java");

    assertEquals(0, orchestrator.run(Settings.def()));
    assertTrue(classExists(currentProject, "com.example.Widget"));
    assertTrue(fileExistsInGraph(currentProject, widgetFile));

    Files.delete(widgetFile);
    assertEquals(0, orchestrator.run(Settings.def()));

    assertFalse(classExists(currentProject, "com.example.Widget"));
    assertFalse(fileExistsInGraph(currentProject, widgetFile));
    assertFalse(methodExists(currentProject, "com.example.Widget.getName()"));
    assertFalse(fieldExists(currentProject, "com.example.Widget#name"));
  }

  @Test
  void reingestionDeletesRemovedDeclarationsFromChangedFile() throws Exception {
    currentProject = PROJECT_BASE + "-removed-declarations";
    sourceDir = buildSampleSourceTree();
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    Path widgetFile = sourceDir.resolve("com/example/Widget.java");

    assertEquals(0, orchestrator.run(Settings.def()));
    Files.writeString(
        widgetFile,
        """
        package com.example;

        public class Widget {
          public String changed() {
            return "changed";
          }
        }
        """);

    assertEquals(0, orchestrator.run(Settings.def()));

    assertTrue(methodExists(currentProject, "com.example.Widget.changed()"));
    assertFalse(methodExists(currentProject, "com.example.Widget.getName()"));
    assertFalse(fieldExists(currentProject, "com.example.Widget#name"));
  }

  @Test
  void reingestionDeletesOwnerRemovedFromChangedFile() throws Exception {
    currentProject = PROJECT_BASE + "-removed-owner";
    sourceDir = Files.createTempDirectory("orch-removed-owner-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Path file = pkgDir.resolve("Types.java");
    Files.writeString(
        file,
        """
        package com.example;

        class Removed {
          void gone() {}
        }

        class Kept {}
        """);
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    assertEquals(0, orchestrator.run(Settings.def()));
    assertTrue(classExists(currentProject, "com.example.Removed"));
    assertTrue(methodExists(currentProject, "com.example.Removed.gone()"));

    Files.writeString(
        file,
        """
        package com.example;

        class Kept {}
        """);

    assertEquals(0, orchestrator.run(Settings.def()));

    assertFalse(classExists(currentProject, "com.example.Removed"));
    assertFalse(methodExists(currentProject, "com.example.Removed.gone()"));
    assertTrue(classExists(currentProject, "com.example.Kept"));
  }

  @Test
  void parallelFailedFileIngestRollsBackStaleCleanup() throws Exception {
    currentProject = PROJECT_BASE + "-parallel-atomic-failure";
    sourceDir = Files.createTempDirectory("orch-parallel-atomic-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Path file = pkgDir.resolve("Atomic.java");
    Files.writeString(
        file,
        """
        package com.example;

        public class Atomic {
          public void oldMethod() {}
        }
        """);
    IngestionOrchestrator normal =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, normal.run(Settings.def()));
    assertTrue(classExists(currentProject, "com.example.Atomic"));
    assertTrue(methodExists(currentProject, "com.example.Atomic.oldMethod()"));
    Files.setLastModifiedTime(
        file, FileTime.fromMillis(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)));

    IngestionOrchestrator failing =
        new IngestionOrchestrator(
            sourceDir, currentProject, 2, driver, new CleanupThenFailingJavaAdapter());
    assertEquals(1, failing.run(Settings.def()));

    assertTrue(fileExistsInGraph(currentProject, file));
    assertTrue(classExists(currentProject, "com.example.Atomic"));
    assertTrue(methodExists(currentProject, "com.example.Atomic.oldMethod()"));
  }

  @Test
  void parseFailurePreservesExistingGraphStateBeforeCleanup() throws Exception {
    currentProject = PROJECT_BASE + "-parse-failure-before-cleanup";
    sourceDir = Files.createTempDirectory("orch-parse-failure-before-cleanup-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Path file = pkgDir.resolve("Atomic.java");
    Files.writeString(
        file,
        """
        package com.example;

        public class Atomic {
          public void oldMethod() {}
        }
        """);
    IngestionOrchestrator normal =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, normal.run(Settings.def()));
    assertTrue(methodExists(currentProject, "com.example.Atomic.oldMethod()"));

    Files.writeString(
        file,
        """
        package com.example;

        public class Atomic {
          public void newMethod() {}
        }
        """);
    IngestionOrchestrator failing =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseFailingJavaAdapter());
    assertEquals(1, failing.run(Settings.def()));

    assertTrue(fileExistsInGraph(currentProject, file));
    assertTrue(methodExists(currentProject, "com.example.Atomic.oldMethod()"));
    assertFalse(methodExists(currentProject, "com.example.Atomic.newMethod()"));
  }

  @Test
  void parallelFailedUncachedExistingJavascriptFileRollsBackStaleCleanup() throws Exception {
    currentProject = PROJECT_BASE + "-parallel-js-uncached";
    sourceDir = Files.createTempDirectory("orch-parallel-js-uncached-src-");
    Path file = sourceDir.resolve("app.ts");
    Files.writeString(file, "export class App {}\n");
    try (Session s = driver.session()) {
      s.run(
              "MERGE (f:File {path: $path, project: $p})"
                  + " SET f.language = 'js', f.lastModified = 1"
                  + " MERGE (c:Class {fqn: 'js.test.App', project: $p})"
                  + " SET c.name = 'App', c.language = 'js', c.kind = 'class',"
                  + "     c.isExternal = false"
                  + " MERGE (f)-[:DEFINES]->(c)",
              Map.of("p", currentProject, "path", file.toString()))
          .consume();
    }
    assertTrue(classExists(currentProject, "js.test.App"));

    IngestionOrchestrator failing =
        new IngestionOrchestrator(
            sourceDir, currentProject, 2, driver, new CleanupThenFailingJsAdapter());
    assertEquals(1, failing.run(Settings.def()));

    assertTrue(fileExistsInGraph(currentProject, file));
    assertTrue(classExists(currentProject, "js.test.App"));
  }

  @Test
  void parallelFailedNewMovedOwnerFileRollsBackCurrentOwnerCleanup() throws Exception {
    currentProject = PROJECT_BASE + "-parallel-new-moved-owner";
    sourceDir = Files.createTempDirectory("orch-parallel-new-moved-owner-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Path oldFile = pkgDir.resolve("ZOld.java");
    Path newFile = pkgDir.resolve("ANew.java");
    Files.writeString(
        oldFile,
        """
        package com.example;

        public class Moved {
          public void oldMethod() {}
        }
        """);
    IngestionOrchestrator normal =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, normal.run(Settings.def()));
    assertTrue(methodExists(currentProject, "com.example.Moved.oldMethod()"));

    Files.setLastModifiedTime(
        oldFile, FileTime.fromMillis(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)));
    Files.writeString(
        newFile,
        """
        package com.example;

        public class Moved {
          public void newMethod() {}
        }
        """);
    IngestionOrchestrator failing =
        new IngestionOrchestrator(
            sourceDir,
            currentProject,
            2,
            driver,
            new CleanupThenFailingJavaAdapter(
                SourceFileDefinitions.of(
                    Set.of("com.example.Moved"),
                    Set.of(),
                    Set.of(),
                    Set.of("com.example.Moved.newMethod()"),
                    Set.of())));

    assertEquals(2, failing.run(Settings.def()));

    assertTrue(methodExists(currentProject, "com.example.Moved.oldMethod()"));
    assertFalse(methodExists(currentProject, "com.example.Moved.newMethod()"));
  }

  @Test
  void reingestionIgnoresDeletedFilesWhenRefreshingRetainedOwnerRelations() throws Exception {
    currentProject = PROJECT_BASE + "-deleted-file-retained-relations";
    sourceDir = Files.createTempDirectory("orch-deleted-file-relations-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Path oldFile = pkgDir.resolve("OldMoved.java");
    Path newFile = pkgDir.resolve("NewMoved.java");
    Files.writeString(
        oldFile,
        """
        package com.example;

        @Deprecated
        class Moved extends OldBase {}
        """);
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    assertEquals(0, orchestrator.run(Settings.def()));

    Files.writeString(
        newFile,
        """
        package com.example;

        class Moved extends NewBase {}
        """);
    Files.delete(oldFile);

    assertEquals(0, orchestrator.run(Settings.def()));

    try (Session s = driver.session()) {
      var row =
          s.run(
                  """
                  MATCH (moved:Class {project: $p, fqn: 'com.example.Moved'})
                  OPTIONAL MATCH (moved)-[:EXTENDS]->(oldParent:Class {project: $p, name: 'OldBase'})
                  OPTIONAL MATCH (moved)-[:EXTENDS]->(newParent:Class {project: $p, name: 'NewBase'})
                  OPTIONAL MATCH (moved)-[:ANNOTATED_WITH]->(deprecated:Annotation {project: $p, name: 'Deprecated'})
                  OPTIONAL MATCH (oldFile:File {project: $p, path: $oldPath})
                  RETURN count(DISTINCT oldParent) AS oldParents,
                         count(DISTINCT newParent) AS newParents,
                         count(DISTINCT deprecated) AS deprecated,
                         count(DISTINCT oldFile) AS oldFiles
                  """,
                  Map.of("p", currentProject, "oldPath", oldFile.toString()))
              .single();

      assertEquals(0, row.get("oldParents").asLong());
      assertEquals(1, row.get("newParents").asLong());
      assertEquals(0, row.get("deprecated").asLong());
      assertEquals(0, row.get("oldFiles").asLong());
    }
  }

  @Test
  void missingFileCleanupPreservesDefinitionsFromOtherSourceRoots() throws Exception {
    currentProject = PROJECT_BASE + "-missing-file-other-root";
    sourceDir = Files.createTempDirectory("orch-missing-file-root-a-");
    Path otherRoot = Files.createTempDirectory("orch-missing-file-root-b-");
    try {
      Path rootA = sourceDir.resolve("com/example");
      Path rootB = otherRoot.resolve("com/example");
      Files.createDirectories(rootA);
      Files.createDirectories(rootB);
      Path removedFile = rootA.resolve("Shared.java");
      Path retainedFile = rootB.resolve("Shared.java");
      String source =
          """
          package com.example;

          public class Shared {}
          """;
      Files.writeString(removedFile, source);
      Files.writeString(retainedFile, source);
      IngestionOrchestrator rootAOrchestrator =
          new IngestionOrchestrator(
              sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
      IngestionOrchestrator rootBOrchestrator =
          new IngestionOrchestrator(
              otherRoot, currentProject, 1, driver, new ParseService(otherRoot));
      assertEquals(0, rootAOrchestrator.run(Settings.def()));
      assertEquals(0, rootBOrchestrator.run(Settings.def()));
      assertTrue(classExists(currentProject, "com.example.Shared"));

      Files.delete(removedFile);

      assertEquals(0, rootAOrchestrator.run(Settings.def()));

      assertFalse(fileExistsInGraph(currentProject, removedFile));
      assertTrue(fileExistsInGraph(currentProject, retainedFile));
      assertTrue(classExists(currentProject, "com.example.Shared"));
    } finally {
      deleteDir(otherRoot);
    }
  }

  @Test
  void missingFileCleanupRefreshesRetainedFileFromOtherSourceRoot() throws Exception {
    currentProject = PROJECT_BASE + "-missing-file-refresh-other-root";
    sourceDir = Files.createTempDirectory("orch-missing-file-refresh-root-a-");
    Path otherRoot = Files.createTempDirectory("orch-missing-file-refresh-root-b-");
    try {
      Path rootA = sourceDir.resolve("com/example");
      Path rootB = otherRoot.resolve("com/example");
      Files.createDirectories(rootA);
      Files.createDirectories(rootB);
      Path removedFile = rootA.resolve("Shared.java");
      Path retainedFile = rootB.resolve("Shared.java");
      Files.writeString(rootA.resolve("Helper.java"), helperSource());
      Files.writeString(rootB.resolve("Helper.java"), helperSource());
      Files.writeString(removedFile, sharedWithHelperCallSource());
      Files.writeString(retainedFile, sharedWithoutHelperCallSource());
      IngestionOrchestrator rootAOrchestrator =
          new IngestionOrchestrator(
              sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
      IngestionOrchestrator rootBOrchestrator =
          new IngestionOrchestrator(
              otherRoot, currentProject, 1, driver, new ParseService(otherRoot));
      assertEquals(0, rootAOrchestrator.run(Settings.def()));
      assertTrue(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
      assertEquals(0, rootBOrchestrator.run(Settings.def()));
      assertTrue(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));

      Files.delete(removedFile);
      assertEquals(0, rootAOrchestrator.run(Settings.def()));

      assertTrue(fileExistsInGraph(currentProject, retainedFile));
      assertFalse(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
    } finally {
      deleteDir(otherRoot);
    }
  }

  @Test
  void missingFileCleanupRefreshesRetainedJavaFileWithStoredSourceRoot() throws Exception {
    currentProject = PROJECT_BASE + "-missing-file-refresh-java-root";
    sourceDir = Files.createTempDirectory("orch-missing-file-refresh-java-root-a-");
    Path otherRoot = Files.createTempDirectory("orch-missing-file-refresh-java-root-b-");
    try {
      Path rootA = sourceDir.resolve("com/example");
      Path rootB = otherRoot.resolve("com/example");
      Files.createDirectories(rootA);
      Files.createDirectories(rootB);
      Path removedFile = rootA.resolve("Shared.java");
      Path retainedFile = rootB.resolve("Shared.java");
      Files.writeString(removedFile, sharedWithoutHelperCallSource());
      Files.writeString(rootB.resolve("Helper.java"), helperSource());
      Files.writeString(retainedFile, sharedWithHelperCallSource());
      IngestionOrchestrator rootAOrchestrator =
          new IngestionOrchestrator(
              sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
      IngestionOrchestrator rootBOrchestrator =
          new IngestionOrchestrator(
              otherRoot, currentProject, 1, driver, new ParseService(otherRoot));
      assertEquals(0, rootAOrchestrator.run(Settings.def()));
      assertFalse(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
      assertEquals(0, rootBOrchestrator.run(Settings.def()));
      assertTrue(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));

      Files.delete(removedFile);
      assertEquals(0, rootAOrchestrator.run(Settings.def()));

      assertTrue(fileExistsInGraph(currentProject, retainedFile));
      assertTrue(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
    } finally {
      deleteDir(otherRoot);
    }
  }

  @Test
  void changedFileCleanupPreservesCallsFromOtherSourceRoots() throws Exception {
    currentProject = PROJECT_BASE + "-changed-file-other-root";
    sourceDir = Files.createTempDirectory("orch-changed-file-root-a-");
    Path otherRoot = Files.createTempDirectory("orch-changed-file-root-b-");
    try {
      Path rootA = sourceDir.resolve("com/example");
      Path rootB = otherRoot.resolve("com/example");
      Files.createDirectories(rootA);
      Files.createDirectories(rootB);
      Path sharedA = rootA.resolve("Shared.java");
      Path sharedB = rootB.resolve("Shared.java");
      String helper =
          """
          package com.example;

          public class Helper {
            public static void go() {}
          }
          """;
      String sharedWithCall =
          """
          package com.example;

          public class Shared {
            public void serve() {
              Helper.go();
            }
          }
          """;
      Files.writeString(rootA.resolve("Helper.java"), helper);
      Files.writeString(rootB.resolve("Helper.java"), helper);
      Files.writeString(sharedA, sharedWithCall);
      Files.writeString(sharedB, sharedWithCall);
      IngestionOrchestrator rootAOrchestrator =
          new IngestionOrchestrator(
              sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
      IngestionOrchestrator rootBOrchestrator =
          new IngestionOrchestrator(
              otherRoot, currentProject, 1, driver, new ParseService(otherRoot));
      assertEquals(0, rootAOrchestrator.run(Settings.def()));
      assertEquals(0, rootBOrchestrator.run(Settings.def()));
      assertTrue(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));

      Files.writeString(
          sharedA,
          """
          package com.example;

          public class Shared {
            public void serve() {}
          }
          """);

      assertEquals(0, rootAOrchestrator.run(Settings.def()));

      assertTrue(fileExistsInGraph(currentProject, sharedB));
      assertTrue(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
    } finally {
      deleteDir(otherRoot);
    }
  }

  @Test
  void changedFileCleanupIgnoresMissingOutsideRootRetainedFiles() throws Exception {
    currentProject = PROJECT_BASE + "-changed-file-missing-other-root";
    sourceDir = Files.createTempDirectory("orch-changed-file-missing-root-a-");
    Path otherRoot = Files.createTempDirectory("orch-changed-file-missing-root-b-");
    try {
      Path rootA = sourceDir.resolve("com/example");
      Files.createDirectories(rootA);
      Path shared = rootA.resolve("Shared.java");
      Path missingRetained = otherRoot.resolve("com/example/Shared.java");
      Files.writeString(rootA.resolve("Helper.java"), helperSource());
      Files.writeString(shared, sharedWithHelperCallSource());
      IngestionOrchestrator orchestrator =
          new IngestionOrchestrator(
              sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
      assertEquals(0, orchestrator.run(Settings.def()));
      assertTrue(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
      try (Session s = driver.session()) {
        s.run(
                """
                MATCH (caller:Method {project: $p, signature: 'com.example.Shared.serve()'})
                MERGE (f:File {path: $path, project: $p})
                SET f.language = 'java', f.lastModified = 1
                MERGE (f)-[:DEFINES]->(caller)
                """,
                Map.of("p", currentProject, "path", missingRetained.toString()))
            .consume();
      }

      Files.writeString(shared, sharedWithoutHelperCallSource());

      assertEquals(0, orchestrator.run(Settings.def()));

      assertFalse(
          callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
    } finally {
      deleteDir(otherRoot);
    }
  }

  @Test
  void changedFileCleanupRetainsSameRootFilesOutsideConfiguredAdapters() throws Exception {
    currentProject = PROJECT_BASE + "-same-root-retained-unconfigured";
    sourceDir = Files.createTempDirectory("orch-same-root-retained-unconfigured-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Path helper = pkgDir.resolve("Helper.java");
    Path shared = pkgDir.resolve("Shared.java");
    Path retainedTs = sourceDir.resolve("retained.ts");
    Files.writeString(helper, helperSource());
    Files.writeString(shared, sharedWithHelperCallSource());
    Files.writeString(retainedTs, "export const retained = true;\n");
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, orchestrator.run(Settings.def()));
    assertTrue(
        callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
    try (Session s = driver.session()) {
      s.run(
              """
              MATCH (caller:Method {project: $p, signature: 'com.example.Shared.serve()'})
              MERGE (f:File {path: $path, project: $p})
              SET f.language = 'js', f.lastModified = 1
              MERGE (f)-[:DEFINES]->(caller)
              """,
              Map.of("p", currentProject, "path", retainedTs.toString()))
          .consume();
    }

    Files.writeString(shared, sharedWithoutHelperCallSource());

    assertEquals(0, orchestrator.run(Settings.def()));

    assertTrue(fileExistsInGraph(currentProject, retainedTs));
    assertTrue(
        callEdgeExists(currentProject, "com.example.Shared.serve()", "com.example.Helper.go()"));
  }

  @Test
  void failedMovedFileIngestSkipsMissingFileCleanup() throws Exception {
    currentProject = PROJECT_BASE + "-failed-moved-file-missing-cleanup";
    sourceDir = Files.createTempDirectory("orch-failed-moved-file-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Path oldFile = pkgDir.resolve("ZOld.java");
    Path newFile = pkgDir.resolve("ANew.java");
    Files.writeString(
        oldFile,
        """
        package com.example;

        public class Moved {
          public void oldMethod() {}
        }
        """);
    IngestionOrchestrator normal =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, normal.run(Settings.def()));
    assertTrue(fileExistsInGraph(currentProject, oldFile));
    assertTrue(methodExists(currentProject, "com.example.Moved.oldMethod()"));

    Files.writeString(
        newFile,
        """
        package com.example;

        public class Moved {
          public void newMethod() {}
        }
        """);
    Files.delete(oldFile);
    IngestionOrchestrator failing =
        new IngestionOrchestrator(
            sourceDir,
            currentProject,
            2,
            driver,
            new CleanupThenFailingJavaAdapter(
                SourceFileDefinitions.of(
                    Set.of("com.example.Moved"),
                    Set.of(),
                    Set.of(),
                    Set.of("com.example.Moved.newMethod()"),
                    Set.of())));

    assertEquals(1, failing.run(Settings.def()));

    assertTrue(fileExistsInGraph(currentProject, oldFile));
    assertTrue(methodExists(currentProject, "com.example.Moved.oldMethod()"));
    assertFalse(methodExists(currentProject, "com.example.Moved.newMethod()"));
  }

  @Test
  void reingestionPreservesOwnerMovedToAnotherFileInSameRun() throws Exception {
    currentProject = PROJECT_BASE + "-moved-owner";
    sourceDir = Files.createTempDirectory("orch-moved-owner-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Path oldFile = pkgDir.resolve("ZOld.java");
    Path newFile = pkgDir.resolve("ANew.java");
    Files.writeString(
        oldFile,
        """
        package com.example;

        public class Moved {
          public void oldMethod() {}
        }
        """);
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, orchestrator.run(Settings.def()));
    assertTrue(classExists(currentProject, "com.example.Moved"));

    Files.writeString(
        newFile,
        """
        package com.example;

        public class Moved {
          public void newMethod() {}
        }
        """);
    Files.writeString(
        oldFile,
        """
        package com.example;

        class ZOld {}
        """);

    assertEquals(0, orchestrator.run(Settings.def()));

    assertTrue(classExists(currentProject, "com.example.Moved"));
    assertTrue(methodExists(currentProject, "com.example.Moved.newMethod()"));
    assertFalse(methodExists(currentProject, "com.example.Moved.oldMethod()"));
  }

  @Test
  void reingestionPreservesInboundCallsWhenMovedFileIsDeleted() throws Exception {
    currentProject = PROJECT_BASE + "-deleted-moved-owner";
    sourceDir = Files.createTempDirectory("orch-deleted-moved-owner-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Path oldFile = pkgDir.resolve("ZMoved.java");
    Path callerFile = pkgDir.resolve("Caller.java");
    Path newFile = pkgDir.resolve("AMoved.java");
    Files.writeString(
        oldFile,
        """
        package com.example;

        class Moved {
          void target() {}
        }
        """);
    Files.writeString(
        callerFile,
        """
        package com.example;

        class Caller {
          void call() {
            new Moved().target();
          }
        }
        """);
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    assertEquals(0, orchestrator.run(Settings.def()));
    assertTrue(
        callEdgeExists(currentProject, "com.example.Caller.call()", "com.example.Moved.target()"));

    Files.writeString(
        newFile,
        """
        package com.example;

        class Moved {
          void target() {}
        }
        """);
    Files.delete(oldFile);

    assertEquals(0, orchestrator.run(Settings.def()));

    assertFalse(fileExistsInGraph(currentProject, oldFile));
    assertTrue(fileExistsInGraph(currentProject, newFile));
    assertTrue(methodExists(currentProject, "com.example.Moved.target()"));
    assertTrue(
        callEdgeExists(currentProject, "com.example.Caller.call()", "com.example.Moved.target()"));
  }

  @Test
  void ingestsConstructorNodes() throws Exception {
    currentProject = PROJECT_BASE + "-ctor";
    sourceDir = Files.createTempDirectory("orch-ctor-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Service.java"),
        """
        package com.example;

        public class Service {
          private final String name;

          public Service(String name) {
            this.name = name;
          }

          public Service() {
            this("default");
          }
        }
        """);

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long ctorCount =
          s.run(
                  "MATCH (:Class {fqn: 'com.example.Service', project: $p})"
                      + "-[:DECLARES]->(m:Method {name: '<init>'})"
                      + " RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(2, ctorCount, "Both constructors of Service must be ingested");
    }
  }

  @Test
  void returnsNonZeroFailureCountForUnparsableFile() throws Exception {
    currentProject = PROJECT_BASE + "-fail";
    sourceDir = Files.createTempDirectory("orch-bad-src-");
    Files.writeString(sourceDir.resolve("Broken.java"), "this {{{ is not java");

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertTrue(failures > 0, "Expected non-zero failures for unparsable .java file");
  }

  @Test
  void ingestsInterfaceNodes() throws Exception {
    currentProject = PROJECT_BASE + "-iface";
    sourceDir = buildSampleSourceTree();

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long ifaceCount =
          s.run(
                  "MATCH (i:Interface {project: $p}) RETURN count(i) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(ifaceCount >= 1, "At least one :Interface node expected");
    }
  }

  @Test
  void ingestsInterfaceExtendsAsInterfaceParent() throws Exception {
    currentProject = PROJECT_BASE + "-iext";
    sourceDir = Files.createTempDirectory("orch-iext-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Printable.java"), "package com.example; public interface Printable {}");
    Files.writeString(
        pkgDir.resolve("Describable.java"),
        "package com.example; public interface Describable extends Printable {}");

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long count =
          s.run(
                  "MATCH (:Interface {project: $p})-[:EXTENDS]->(parent:Interface)"
                      + " RETURN count(parent) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(count >= 1, "Interface parent must be stored as :Interface, not :Class");
    }
  }

  @Test
  void ingestsNestedClassWithCorrectFqn() throws Exception {
    currentProject = PROJECT_BASE + "-nested";
    sourceDir = Files.createTempDirectory("orch-nested-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Outer.java"),
        """
        package com.example;
        public class Outer {
          public class Inner {}
        }
        """);

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long outerCount =
          s.run(
                  "MATCH (c:Class {fqn: 'com.example.Outer', project: $p}) RETURN count(c) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(1, outerCount, "Outer class must have correct FQN");

      long innerCount =
          s.run(
                  "MATCH (c:Class {fqn: 'com.example.Outer$Inner', project: $p})"
                      + " RETURN count(c) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(1, innerCount, "Nested class must use $-separated FQN");
    }
  }

  @Test
  void crossFileCallsEdgesCreated() throws Exception {
    currentProject = PROJECT_BASE + "-xfile";
    sourceDir = Files.createTempDirectory("orch-xfile-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);

    // "AAA" sorts before "BBB" — guarantees AAA is processed first (inline MERGE-on-callee test)
    Files.writeString(
        pkgDir.resolve("AAACaller.java"),
        """
        package com.example;

        public class AAACaller {
          public void doWork() {
            new BBBService().serve();
          }
        }
        """);
    Files.writeString(
        pkgDir.resolve("BBBService.java"),
        """
        package com.example;

        public class BBBService {
          public void serve() {}
        }
        """);

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long callEdges =
          s.run(
                  "MATCH (:Method {project: $p})-[:CALLS]->(:Method {project: $p})"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(callEdges >= 1, "Cross-file CALLS edge must exist (AAACaller -> BBBService)");

      List<String> ownerEdges =
          s.run(
                  "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {project: $p})"
                      + " WHERE caller.ownerFqn IS NOT NULL"
                      + " AND callee.ownerFqn IS NOT NULL"
                      + " AND caller.ownerFqn <> callee.ownerFqn"
                      + " RETURN caller.ownerDisplayName + ' -> ' + callee.ownerDisplayName"
                      + " AS edge ORDER BY edge",
                  Map.of("p", currentProject))
              .list(r -> r.get("edge").asString());
      assertTrue(
          ownerEdges.contains("AAACaller -> BBBService"),
          "CALLS summaries should use persisted Method owner metadata");
    }
  }

  @Test
  void broadSourceRootCreatesCrossFileCallEdges() throws Exception {
    currentProject = PROJECT_BASE + "-broad-xfile";
    sourceDir = Files.createTempDirectory("orch-broad-xfile-src-");
    Path pkgDir = sourceDir.resolve("src/main/java/com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("AAACaller.java"),
        """
        package com.example;

        public class AAACaller {
          public void doWork() {
            new BBBService().serve();
          }
        }
        """);
    Files.writeString(
        pkgDir.resolve("BBBService.java"),
        """
        package com.example;

        public class BBBService {
          public void serve() {}
        }
        """);

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    assertTrue(
        callEdgeExists(
            currentProject, "com.example.AAACaller.doWork()", "com.example.BBBService.serve()"));
  }

  @Test
  void incrementalRunBackfillsOwnerMetadataForUnchangedMethods() throws Exception {
    currentProject = PROJECT_BASE + "-incremental-owner-backfill";
    sourceDir = Files.createTempDirectory("orch-incremental-owner-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("AAACaller.java"),
        """
        package com.example;

        public class AAACaller {
          public void doWork() {
            new BBBService().serve();
          }
        }
        """);
    Files.writeString(
        pkgDir.resolve("BBBService.java"),
        """
        package com.example;

        public class BBBService {
          public void serve() {}
        }
        """);

    var orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, orchestrator.run(Settings.def()));

    try (Session s = driver.session()) {
      s.run(
              "MATCH (m:Method {project: $p}) REMOVE m.ownerFqn, m.ownerDisplayName",
              Map.of("p", currentProject))
          .consume();
    }

    assertEquals(0, orchestrator.run(Settings.def()));

    try (Session s = driver.session()) {
      long missingOwnerMetadata =
          s.run(
                  "MATCH (owner)-[:DECLARES]->(m:Method {project: $p})"
                      + " WHERE owner.project = $p"
                      + " AND (owner:Class OR owner:Interface OR owner:Annotation)"
                      + " AND (m.ownerFqn IS NULL OR m.ownerDisplayName IS NULL)"
                      + " RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(0, missingOwnerMetadata, "Incremental run must backfill skipped methods");

      List<String> ownerEdges =
          s.run(
                  "MATCH (caller:Method {project: $p})-[:CALLS]->(callee:Method {project: $p})"
                      + " WHERE caller.ownerFqn IS NOT NULL"
                      + " AND callee.ownerFqn IS NOT NULL"
                      + " AND caller.ownerFqn <> callee.ownerFqn"
                      + " RETURN caller.ownerDisplayName + ' -> ' + callee.ownerDisplayName"
                      + " AS edge ORDER BY edge",
                  Map.of("p", currentProject))
              .list(r -> r.get("edge").asString());
      assertTrue(ownerEdges.contains("AAACaller -> BBBService"));
    }
  }

  @Test
  void incrementalRunReingestsUnchangedFilesMissingCodeChunks() throws Exception {
    currentProject = PROJECT_BASE + "-incremental-code-chunk-backfill";
    sourceDir = Files.createTempDirectory("orch-incremental-chunk-src-");
    Path sourceFile = sourceDir.resolve("com/example/Widget.java");
    Files.createDirectories(sourceFile.getParent());
    Files.writeString(
        sourceFile,
        """
        package com.example;

        /** Searchable widget docs. */
        public class Widget {
          /** Searchable method docs. */
          public String name() {
            return "widget";
          }
        }
        """);

    var orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, orchestrator.run(Settings.def()));

    try (Session s = driver.session()) {
      s.run(
              "MATCH (chunk:CodeChunk {project: $p, path: $path}) DETACH DELETE chunk",
              Map.of("p", currentProject, "path", sourceFile.toString()))
          .consume();
    }

    assertEquals(0, orchestrator.run(Settings.def()));
    List<String> backfilledChunks = codeChunkTexts(currentProject, sourceFile);
    assertTrue(backfilledChunks.stream().anyMatch(text -> text.contains("Searchable widget docs")));
    assertTrue(backfilledChunks.stream().anyMatch(text -> text.contains("Searchable method docs")));
  }

  @Test
  void incrementalRunSkipsPrepareForUnchangedFiles() throws Exception {
    currentProject = PROJECT_BASE + "-incremental-prepare-skip";
    sourceDir = Files.createTempDirectory("orch-incremental-prepare-skip-src-");
    Path sourceFile = sourceDir.resolve("Sample.java");
    Files.writeString(sourceFile, "unchanged");
    PrepareCountingAdapter adapter = new PrepareCountingAdapter();
    var orchestrator = new IngestionOrchestrator(sourceDir, currentProject, 1, driver, adapter);

    assertEquals(0, orchestrator.run(Settings.def()));
    assertEquals(1, adapter.prepares());
    try (Session s = driver.session()) {
      s.run(
              "CREATE (:CodeChunk {project: $p, path: $path})",
              Map.of("p", currentProject, "path", sourceFile.toString()))
          .consume();
    }

    assertEquals(0, orchestrator.run(Settings.def()));
    assertEquals(1, adapter.prepares());
  }

  @Test
  void runReportsAdapterPrepareFailureAsFileFailure() throws Exception {
    currentProject = PROJECT_BASE + "-prepare-failure";
    sourceDir = Files.createTempDirectory("orch-prepare-failure-src-");
    Path sourceFile = sourceDir.resolve("Sample.java");
    Files.writeString(sourceFile, "changed");
    PrepareCountingAdapter adapter =
        new PrepareCountingAdapter(new ProcessingException("runtime unavailable"));
    var orchestrator = new IngestionOrchestrator(sourceDir, currentProject, 1, driver, adapter);

    assertEquals(1, orchestrator.run(Settings.def()));
    assertEquals(1, adapter.prepares());
  }

  @Test
  void incrementalRunReingestsChangedInvalidAndNewFiles() throws Exception {
    currentProject = PROJECT_BASE + "-incremental-changes";
    sourceDir = buildSampleSourceTree();

    var orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, orchestrator.run(Settings.def()));

    Path widgetFile = sourceDir.resolve("com/example/Widget.java");
    Files.writeString(
        widgetFile,
        """
        package com.example;

        public class Widget {
          public String changed() {
            return "changed";
          }
        }
        """);
    Files.setLastModifiedTime(
        widgetFile, FileTime.fromMillis(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)));

    Path newFile = sourceDir.resolve("com/example/NewThing.java");
    Files.writeString(
        newFile,
        """
        package com.example;

        public class NewThing {}
        """);

    Path interfaceFile = sourceDir.resolve("com/example/Describable.java");
    try (Session s = driver.session()) {
      s.run(
              "MATCH (f:File {project: $p, path: $path}) SET f.lastModified = 0",
              Map.of("p", currentProject, "path", interfaceFile.toString()))
          .consume();
    }

    assertEquals(0, orchestrator.run(Settings.def()));

    try (Session s = driver.session()) {
      long newThingCount =
          s.run(
                  "MATCH (c:Class {project: $p, fqn: 'com.example.NewThing'}) RETURN count(c) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      long changedMethodCount =
          s.run(
                  "MATCH (m:Method {project: $p, signature: 'com.example.Widget.changed()'})"
                      + " RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();

      assertEquals(1, newThingCount);
      assertEquals(1, changedMethodCount);
    }
  }

  @Test
  void incrementalRunPreservesIncomingCallEdgesWhenCalleeChanges() throws Exception {
    currentProject = PROJECT_BASE + "-incremental-callee-change";
    sourceDir = Files.createTempDirectory("orch-incremental-callee-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("AAACaller.java"),
        """
        package com.example;

        public class AAACaller {
          public void doWork() {
            new BBBService().serve();
          }
        }
        """);
    Path serviceFile = pkgDir.resolve("BBBService.java");
    Files.writeString(
        serviceFile,
        """
        package com.example;

        public class BBBService {
          public void serve() {}
        }
        """);
    IngestionOrchestrator orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));
    assertEquals(0, orchestrator.run(Settings.def()));
    assertTrue(
        callEdgeExists(
            currentProject, "com.example.AAACaller.doWork()", "com.example.BBBService.serve()"));

    Files.writeString(
        serviceFile,
        """
        package com.example;

        public class BBBService {
          private int calls;

          public void serve() {
            calls++;
          }
        }
        """);
    Files.setLastModifiedTime(
        serviceFile,
        FileTime.fromMillis(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)));

    assertEquals(0, orchestrator.run(Settings.def()));

    assertTrue(
        callEdgeExists(
            currentProject, "com.example.AAACaller.doWork()", "com.example.BBBService.serve()"));
    assertTrue(fieldExists(currentProject, "com.example.BBBService#calls"));
  }

  @Test
  void incrementalModeIsDisabledWhenWipingProjectData() throws Exception {
    currentProject = PROJECT_BASE + "-incremental-wipe";
    sourceDir = buildSampleSourceTree();
    var orchestrator =
        new IngestionOrchestrator(
            sourceDir, currentProject, 1, driver, new ParseService(sourceDir));

    assertEquals(0, orchestrator.run(Settings.def()));
    assertEquals(0, orchestrator.run(Settings.wipeProjCodeOnly()));
    assertEquals(0, orchestrator.run(new Settings(true, false, false, false, false)));

    try (Session s = driver.session()) {
      long classCount =
          s.run("MATCH (c:Class {project: $p}) RETURN count(c) AS n", Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();

      assertTrue(classCount >= 1);
    }
  }

  /**
   * Verifies that inline CALLS creation works when the callee file is processed after the caller —
   * the MERGE-on-callee trick creates a placeholder that is upgraded when BBBService is ingested.
   */
  @Test
  void inlineCallEdgesCreatedWhenCalleeProcessedLater() throws Exception {
    currentProject = PROJECT_BASE + "-inline";
    sourceDir = Files.createTempDirectory("orch-inline-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);

    // "AAA" sorts before "ZZZ" — caller always processed before callee
    Files.writeString(
        pkgDir.resolve("AAAInlineCaller.java"),
        """
        package com.example;

        public class AAAInlineCaller {
          public static void call() {
            ZZZInlineTarget.process();
          }
        }
        """);
    Files.writeString(
        pkgDir.resolve("ZZZInlineTarget.java"),
        """
        package com.example;

        public class ZZZInlineTarget {
          public static void process() {}
        }
        """);

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long callEdges =
          s.run(
                  "MATCH (:Method {name: 'call', project: $p})"
                      + "-[:CALLS]->(:Method {name: 'process', project: $p})"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(
          1, callEdges, "CALLS edge must exist even though callee was processed after caller");

      // The callee must be a fully populated node (not a phantom) after ingestion
      long phantomMethods =
          s.run(
                  "MATCH (m:Method {project: $p}) WHERE m.startLine IS NULL RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(0, phantomMethods, "No phantom Method nodes must remain after phantom cleanup");
    }
  }

  /** Verifies that phantom Method nodes created for JDK/external callees are cleaned up. */
  @Test
  void phantomExternalMethodNodesCleanedUpAfterIngestion() throws Exception {
    currentProject = PROJECT_BASE + "-phantom";
    sourceDir = Files.createTempDirectory("orch-phantom-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);

    // Calls String.length() — a JDK method that will never be ingested
    Files.writeString(
        pkgDir.resolve("PhantomCaller.java"),
        """
        package com.example;

        public class PhantomCaller {
          public int getLen(String s) {
            return s.length();
          }
        }
        """);

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long phantomMethods =
          s.run(
                  "MATCH (m:Method {project: $p}) WHERE m.startLine IS NULL RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(
          0, phantomMethods, "Phantom external Method nodes must be removed after ingestion");
    }
  }

  /**
   * 20 caller files all call the same static method on a target that is alphabetically last —
   * verifying that all 20 CALLS edges are created via the MERGE-on-callee approach even though the
   * target file is processed after all callers.
   */
  @Test
  void manyCrossFileCallsAllCreatedInline() throws Exception {
    currentProject = PROJECT_BASE + "-many-inline";
    sourceDir = Files.createTempDirectory("orch-many-inline-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);

    // "ZZZ" sorts after all "Caller" files — target always processed last
    Files.writeString(
        pkgDir.resolve("ZZZSharedTarget.java"),
        """
        package com.example;

        public class ZZZSharedTarget {
          public static void work() {}
        }
        """);

    int callerCount = 20;
    for (int i = 0; i < callerCount; i++) {
      Files.writeString(
          pkgDir.resolve(String.format("Caller%02d.java", i)),
          """
          package com.example;

          public class Caller%02d {
            public void run() {
              ZZZSharedTarget.work();
            }
          }
          """
              .formatted(i));
    }

    int failures =
        new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
            .run(Settings.def());

    assertEquals(0, failures);
    try (Session s = driver.session()) {
      long callEdges =
          s.run(
                  "MATCH (:Method {project: $p})-[:CALLS]->(:Method {name: 'work', project: $p})"
                      + " RETURN count(*) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(
          callerCount,
          callEdges,
          "Each caller must have exactly one CALLS edge to ZZZSharedTarget.work()");

      long phantomMethods =
          s.run(
                  "MATCH (m:Method {project: $p}) WHERE m.startLine IS NULL RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(0, phantomMethods, "No phantom nodes must survive after ingestion");
    }
  }

  /**
   * A class with no declared constructor must have a synthesized {@code <init>()} node so that
   * {@code new ClassName()} CALLS edges survive phantom cleanup.
   */
  @Test
  void callsEdgeToImplicitDefaultConstructorPreservedAfterIngestion() throws Exception {
    currentProject = PROJECT_BASE + "-implicit-ctor";
    sourceDir = Files.createTempDirectory("orch-implicit-ctor-src-");
    Path pkgDir = sourceDir.resolve("com/example");
    Files.createDirectories(pkgDir);
    Files.writeString(
        pkgDir.resolve("Service.java"),
        """
        package com.example;
        public class Service {
          public void serve() {}
        }
        """);
    Files.writeString(
        pkgDir.resolve("Client.java"),
        """
        package com.example;
        public class Client {
          public void run() {
            new Service().serve();
          }
        }
        """);

    new IngestionOrchestrator(sourceDir, currentProject, 1, driver, new ParseService(sourceDir))
        .run(Settings.def());

    try (Session s = driver.session()) {
      long initEdges =
          s.run(
                  "MATCH (:Method {project: $p})-[:CALLS]->(m:Method {name: '<init>', project: $p})"
                      + " RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertTrue(
          initEdges >= 1,
          "CALLS edge to implicit default constructor must survive phantom cleanup");

      long phantomMethods =
          s.run(
                  "MATCH (m:Method {project: $p}) WHERE m.startLine IS NULL RETURN count(m) AS n",
                  Map.of("p", currentProject))
              .single()
              .get("n")
              .asLong();
      assertEquals(0, phantomMethods, "No phantom nodes must remain after ingestion");
    }
  }

  /** Sequential and parallel ingestion must produce the same CALLS edge count. */
  @Test
  void sequentialAndParallelProduceSameCallEdgeCount() throws Exception {
    Path seqDir = null;
    Path parDir = null;
    String seqProject = PROJECT_BASE + "-calls-seq";
    String parProject = PROJECT_BASE + "-calls-par";
    try {
      seqDir = Files.createTempDirectory("orch-calls-seq-");
      parDir = Files.createTempDirectory("orch-calls-par-");
      for (Path dir : List.of(seqDir, parDir)) {
        Path pkgDir = dir.resolve("com/example");
        Files.createDirectories(pkgDir);
        Files.writeString(
            pkgDir.resolve("Svc.java"),
            """
            package com.example;
            public class Svc { public static void go() {} }
            """);
        for (int i = 0; i < 5; i++) {
          Files.writeString(
              pkgDir.resolve("Client" + i + ".java"),
              """
              package com.example;
              public class Client%s { public void run() { Svc.go(); } }
              """
                  .formatted(i));
        }
      }

      new IngestionOrchestrator(seqDir, seqProject, 1, driver, new ParseService(seqDir))
          .run(Settings.def());
      new IngestionOrchestrator(parDir, parProject, 4, driver, new ParseService(parDir))
          .run(Settings.def());

      try (Session s = driver.session()) {
        long seqCalls =
            s.run(
                    "MATCH (:Method {project: $p})-[:CALLS]->(:Method {project: $p})"
                        + " RETURN count(*) AS n",
                    Map.of("p", seqProject))
                .single()
                .get("n")
                .asLong();
        long parCalls =
            s.run(
                    "MATCH (:Method {project: $p})-[:CALLS]->(:Method {project: $p})"
                        + " RETURN count(*) AS n",
                    Map.of("p", parProject))
                .single()
                .get("n")
                .asLong();
        assertEquals(
            seqCalls, parCalls, "Sequential and parallel must produce equal CALLS edge count");
        assertTrue(seqCalls >= 5, "At least 5 CALLS edges expected (one per client)");
      }
    } finally {
      wipeProjectCode(seqProject);
      wipeProjectCode(parProject);
      deleteDir(seqDir);
      deleteDir(parDir);
    }
  }
}
