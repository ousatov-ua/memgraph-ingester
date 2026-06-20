package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalyzer;
import io.github.ousatov.tools.memgraph.exe.rag.PythonCodeChunkBuilder;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.python.PythonGraphWriter;
import io.github.ousatov.tools.memgraph.vo.analysis.PythonAnalysis;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CPython AST-backed adapter for Python source structure.
 *
 * @author Oleksii Usatov
 */
public final class PythonLanguageAdapter extends AbstractModuleLanguageAdapter<PythonAnalysis> {

  private static final Logger log = LoggerFactory.getLogger(PythonLanguageAdapter.class);
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of(
          Const.Files.NODE_MODULES,
          Const.Files.PYCACHE,
          Const.Files.VIRTUAL_ENV,
          Const.Files.UV_CACHE,
          Const.Files.VENV,
          Const.Files.TOX,
          Const.Files.NOX,
          Const.Files.SITE_PACKAGES,
          Const.Files.BUILD,
          Const.Files.TARGET,
          Const.Files.DIST);

  private final PythonAnalyzer analyzer;
  private final PythonCodeChunkBuilder codeChunks = new PythonCodeChunkBuilder();

  public PythonLanguageAdapter(PythonAnalyzer analyzer) {
    this.analyzer = analyzer;
  }

  @Override
  public SourceLanguage language() {
    return SourceLanguage.PYTHON;
  }

  @Override
  public boolean accepts(Path file) {
    String lower = file.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    return !isInSkippedDirectory(file)
        && (lower.endsWith(Const.Files.PYTHON_EXTENSION)
            || lower.endsWith(Const.Files.PYTHON_STUB_EXTENSION));
  }

  @Override
  public boolean shouldVisitDirectory(Path directory) {
    return LanguageAdapter.shouldVisitSourceDirectory(directory)
        && !isInSkippedDirectory(directory);
  }

  @Override
  public LanguageAdapter<PythonAnalysis> forSourceRoot(Path sourceRoot) {
    return new PythonLanguageAdapter(analyzer.withSourceRoot(sourceRoot));
  }

  @Override
  public Optional<PythonAnalysis> parse(Path file) {
    try {
      return Optional.of(analyzer.analyze(file));
    } catch (Exception e) {
      log.warn("Failed to parse {}: {}", file, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public List<ParseResult<PythonAnalysis>> parseBatch(List<Path> files) {
    try {
      List<PythonAnalysis> analyses = analyzer.analyzeBatch(files);
      if (analyses.size() != files.size()) {
        throw new IllegalStateException("Python analyzer returned incomplete batch");
      }
      List<ParseResult<PythonAnalysis>> results = new ArrayList<>(files.size());
      for (int i = 0; i < files.size(); i++) {
        results.add(ParseResult.parsed(files.get(i), analyses.get(i)));
      }
      return List.copyOf(results);
    } catch (RuntimeException e) {
      log.warn("Failed to batch parse {} Python file(s): {}", files.size(), e.getMessage());
      return parseBatchOneByOne(files);
    }
  }

  @Override
  public boolean supportsBatchParsing() {
    return true;
  }

  @Override
  public void prepare() {
    analyzer.prepareRuntime();
  }

  @Override
  public boolean write(GraphWriter writer, Path file, PythonAnalysis analysis) {
    PythonGraphWriter pythonWriter = new PythonGraphWriter(writer.dependencies());
    try {
      writer.upsertFile(file, language());
      writer.deleteStaleModuleDefinitionsForFile(file, analysis.moduleFqn(), language());
      writer.upsertPackage(analysis.packageName(), language());
      pythonWriter.upsertModule(
          file,
          analysis.packageName(),
          analysis.moduleFqn(),
          analysis.moduleName(),
          analysis.modulePath(),
          analysis.startLine(),
          analysis.endLine());
      analysis
          .types()
          .forEach(
              type ->
                  pythonWriter.upsertClass(
                      file,
                      analysis.packageName(),
                      type.fqn(),
                      type.name(),
                      analysis.modulePath(),
                      type.framework(),
                      type.isAbstract(),
                      type.hasConstructor(),
                      type.startLine(),
                      type.endLine()));
      analysis
          .relations()
          .forEach(
              relation -> {
                if (Params.CLASS_EXTENDS.equals(relation.kind())) {
                  pythonWriter.upsertExtendsClass(relation.childFqn(), relation.targetFqn());
                }
              });
      upsertMembers(pythonWriter, file, analysis.members());
      upsertAnnotations(writer, analysis.annotations());
      upsertCalls(writer, analysis.calls(), true);
      writer.replaceCodeChunksForFile(file, codeChunks.build(file, analysis));
      return true;
    } catch (RuntimeException e) {
      if (GraphWriter.isRetryable(e)) {
        throw e;
      }
      log.warn("Failed to ingest {}: {}", file, e.getMessage());
      return false;
    }
  }

  private static boolean isInSkippedDirectory(Path path) {
    for (Path part : path) {
      if (SKIPPED_DIRECTORIES.contains(part.toString())) {
        return true;
      }
    }
    return false;
  }
}
