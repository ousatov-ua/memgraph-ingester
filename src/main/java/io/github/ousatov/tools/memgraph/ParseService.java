package io.github.ousatov.tools.memgraph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configures JavaParser once and vends a per-thread parser instance.
 *
 * <p>{@link JavaParser} is not thread-safe; each thread receives its own instance via a {@link
 * ThreadLocal}, sharing only the immutable {@link ParserConfiguration}.
 *
 * @author Oleksii Usatov
 */
public final class ParseService {

  private static final Logger log = LoggerFactory.getLogger(ParseService.class);

  private static final ThreadLocal<JavaParser> PARSER = new ThreadLocal<>();

  private final ParserConfiguration config;

  /**
   * @param sourceRoot root source directory, used by {@link JavaParserTypeSolver} for symbol
   *     resolution
   */
  public ParseService(Path sourceRoot) {
    this.config = buildConfig(sourceRoot);
  }

  /**
   * Returns the {@link JavaParser} for the calling thread, creating one on first call.
   *
   * @return thread-local parser instance
   */
  public JavaParser parserForCurrentThread() {
    JavaParser parser = PARSER.get();
    if (parser == null) {
      parser = new JavaParser(config);
      PARSER.set(parser);
    }
    return parser;
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

  private static ParserConfiguration buildConfig(Path sourceRoot) {
    CombinedTypeSolver solver = new CombinedTypeSolver();
    solver.add(new JavaParserTypeSolver(sourceRoot));
    solver.add(new ReflectionTypeSolver());
    ParserConfiguration cfg = new ParserConfiguration();
    cfg.setSymbolResolver(new JavaSymbolSolver(solver));
    cfg.setLanguageLevel(LanguageLevel.JAVA_25);
    return cfg;
  }
}
