package io.github.ousatov.tools.memgraph.exe.analyze;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures JavaParser once and vends a per-thread parser instance.
 *
 * <p>{@link JavaParser} is not thread-safe; each thread receives its own instance via a {@link
 * ThreadLocal}, sharing only the immutable {@link ParserConfiguration}. The {@code ThreadLocal} is
 * an instance field so multiple {@code ParseService} instances (with different source roots) each
 * maintain independent per-thread parsers.
 *
 * @author Oleksii Usatov
 */
public final class ParseService {

  private static final Logger log = LoggerFactory.getLogger(ParseService.class);

  private final ParserConfiguration config;
  private final ThreadLocal<JavaParser> parser;

  /**
   * @param sourceRoot root source directory, used by {@link JavaParserTypeSolver} for symbol
   *     resolution
   */
  public ParseService(Path sourceRoot) {
    this(sourceRoot, List.of());
  }

  /**
   * @param sourceRoot root source directory for symbol resolution
   * @param classpathEntries JAR files to add to the symbol solver for improved type resolution
   */
  public ParseService(Path sourceRoot, List<Path> classpathEntries) {
    this.config = buildConfig(sourceRoot, classpathEntries);
    this.parser = ThreadLocal.withInitial(() -> new JavaParser(config));
  }

  /**
   * Returns the {@link JavaParser} for the calling thread, creating one on first call.
   *
   * @return thread-local parser instance
   */
  public JavaParser parserForCurrentThread() {
    return parser.get();
  }

  /**
   * Parses {@code file} and returns the compilation unit, or empty on any failure.
   *
   * @param file source file to parse
   * @return parsed compilation unit, or empty if parsing fails
   */
  public Optional<CompilationUnit> parse(Path file) {
    try {
      var result = parserForCurrentThread().parse(file);
      if (!result.isSuccessful() || result.getResult().isEmpty()) {
        log.warn("Failed to parse {}: {}", file, result.getProblems());
        return Optional.empty();
      }
      return result.getResult();
    } catch (Exception e) {
      log.warn("Failed to parse {}: {}", file, e.getMessage());
      return Optional.empty();
    }
  }

  private static ParserConfiguration buildConfig(Path sourceRoot, List<Path> classpathEntries) {
    CombinedTypeSolver solver = new CombinedTypeSolver();
    addSourceRoots(solver, sourceRoot);
    solver.add(new ReflectionTypeSolver());
    for (Path jar : classpathEntries) {
      addJarToSolver(solver, jar);
    }
    if (!classpathEntries.isEmpty()) {
      log.info("Added {} JAR(s) to the symbol solver classpath", classpathEntries.size());
    }
    ParserConfiguration cfg = new ParserConfiguration();
    cfg.setSymbolResolver(new JavaSymbolSolver(solver));
    cfg.setLanguageLevel(LanguageLevel.JAVA_25);
    return cfg;
  }

  /**
   * Tries to add {@code jar} to the solver directly, then falls back to a filtered copy that omits
   * entries (e.g. {@code module-info.class}) that cause Javassist to fail.
   */
  private static void addJarToSolver(CombinedTypeSolver solver, Path jar) {
    try {
      solver.add(new JarTypeSolver(jar));
    } catch (Exception first) {
      log.debug(
          "JarTypeSolver failed for {}, retrying with filtered JAR: {}", jar, first.getMessage());
      tryAddFilteredJar(solver, jar, first);
    }
  }

  private static void tryAddFilteredJar(CombinedTypeSolver solver, Path jar, Exception original) {
    try {
      Path filtered = createFilteredJar(jar);
      solver.add(new JarTypeSolver(filtered));
      log.info("Added filtered JAR to solver (skipped problematic entries): {}", jar);
    } catch (Exception _) {
      log.warn("Could not add JAR to solver: {}: {}", jar, original.getMessage());
    }
  }

  /**
   * Returns a temp copy of {@code originalJar} with entries that break Javassist removed. The temp
   * file is registered for deletion on JVM exit.
   *
   * @param originalJar source JAR path
   * @return path to the filtered temp JAR
   * @throws IOException on read/write failure
   */
  static Path createFilteredJar(Path originalJar) throws IOException {
    Path temp = Files.createTempFile("memgraph-ingester-filtered-", ".jar");
    temp.toFile().deleteOnExit();
    try (ZipFile zipFile = new ZipFile(originalJar.toFile());
        ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
      var entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (shouldExcludeEntry(entry.getName())) {
          log.debug("Filtered out JAR entry: {}", entry.getName());
          continue;
        }
        try (InputStream in = zipFile.getInputStream(entry)) {
          out.putNextEntry(new ZipEntry(entry.getName()));
          in.transferTo(out);
          out.closeEntry();
        } catch (IOException e) {
          log.debug("Skipped JAR entry '{}': {}", entry.getName(), e.getMessage());
        }
      }
    }
    return temp;
  }

  /**
   * Returns {@code true} for entries that Javassist cannot parse, such as {@code module-info.class}
   * and its multi-release variants.
   */
  private static boolean shouldExcludeEntry(String name) {
    return name.equals("module-info.class")
        || name.matches("META-INF/versions/\\d+/module-info\\.class");
  }

  /**
   * Registers {@code sourceRoot} as a type solver and auto-detects Maven-standard subdirectories
   * ({@code main/java}, {@code test/java}) if they exist under it.
   */
  private static void addSourceRoots(CombinedTypeSolver solver, Path sourceRoot) {
    solver.add(new JavaParserTypeSolver(sourceRoot));
    Path mainJava = sourceRoot.resolve("main/java");
    if (Files.isDirectory(mainJava)) {
      solver.add(new JavaParserTypeSolver(mainJava));
      log.info("Auto-detected source root: {}", mainJava);
    }
    Path testJava = sourceRoot.resolve("test/java");
    if (Files.isDirectory(testJava)) {
      solver.add(new JavaParserTypeSolver(testJava));
      log.info("Auto-detected source root: {}", testJava);
    }
  }
}
