package io.github.ousatov.tools.memgraph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
    solver.add(new JavaParserTypeSolver(sourceRoot));
    solver.add(new ReflectionTypeSolver());
    for (Path jar : classpathEntries) {
      try {
        solver.add(new JarTypeSolver(jar));
      } catch (Exception e) {
        log.warn("Could not add JAR to solver: {}: {}", jar, e.getMessage());
      }
    }
    if (!classpathEntries.isEmpty()) {
      log.info("Added {} JAR(s) to the symbol solver classpath", classpathEntries.size());
    }
    ParserConfiguration cfg = new ParserConfiguration();
    cfg.setSymbolResolver(new JavaSymbolSolver(solver));
    cfg.setLanguageLevel(LanguageLevel.JAVA_25);
    return cfg;
  }
}
