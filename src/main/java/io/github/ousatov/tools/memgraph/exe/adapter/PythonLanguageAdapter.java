package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalysis;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalyzer;
import io.github.ousatov.tools.memgraph.exe.rag.PythonCodeChunkBuilder;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.python.PythonGraphWriter;
import java.nio.file.Path;
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
  public boolean write(GraphWriter writer, Path file, PythonAnalysis analysis) {
    PythonGraphWriter pythonWriter = new PythonGraphWriter(writer.dependencies());
    try {
      writer.upsertFile(file, language());
      writer.deleteStalePythonDefinitionsForFile(file, analysis.moduleFqn());
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
