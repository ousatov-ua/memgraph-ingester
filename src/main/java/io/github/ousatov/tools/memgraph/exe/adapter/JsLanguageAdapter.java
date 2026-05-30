package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.JsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.rag.JsCodeChunkBuilder;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.js.JsGraphWriter;
import io.github.ousatov.tools.memgraph.vo.analysis.JsAnalysis;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Node/TypeScript-backed adapter for JavaScript and TypeScript source structure.
 *
 * @author Oleksii Usatov
 */
public final class JsLanguageAdapter extends AbstractModuleLanguageAdapter<JsAnalysis> {

  private static final Logger log = LoggerFactory.getLogger(JsLanguageAdapter.class);
  private static final Set<String> EXTENSIONS =
      Set.of(
          Const.Files.JAVASCRIPT_EXTENSION,
          Const.Files.JSX_EXTENSION,
          Const.Files.TYPESCRIPT_EXTENSION,
          Const.Files.TSX_EXTENSION,
          Const.Files.TYPESCRIPT_MODULE_EXTENSION,
          Const.Files.TYPESCRIPT_COMMONJS_EXTENSION,
          Const.Files.JAVASCRIPT_MODULE_EXTENSION,
          Const.Files.COMMONJS_EXTENSION);

  private final JsAnalyzer analyzer;
  private final JsCodeChunkBuilder codeChunks = new JsCodeChunkBuilder();

  public JsLanguageAdapter(JsAnalyzer analyzer) {
    this.analyzer = analyzer;
  }

  @Override
  public SourceLanguage language() {
    return SourceLanguage.JAVASCRIPT;
  }

  @Override
  public boolean accepts(Path file) {
    String path = file.toString().replace('\\', '/');
    String lower = path.toLowerCase(Locale.ROOT);
    if (isInNodeModules(file)) {
      return false;
    }
    return EXTENSIONS.stream().anyMatch(lower::endsWith);
  }

  @Override
  public boolean shouldVisitDirectory(Path directory) {
    return LanguageAdapter.shouldVisitSourceDirectory(directory) && !isInNodeModules(directory);
  }

  @Override
  public LanguageAdapter<JsAnalysis> forSourceRoot(Path sourceRoot) {
    return new JsLanguageAdapter(analyzer.withSourceRoot(sourceRoot));
  }

  @Override
  public Optional<JsAnalysis> parse(Path file) {
    try {
      return Optional.of(analyzer.analyze(file));
    } catch (Exception e) {
      log.warn("Failed to parse {}: {}", file, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public boolean write(GraphWriter writer, Path file, JsAnalysis analysis) {
    JsGraphWriter jsWriter = new JsGraphWriter(writer.dependencies());
    try {
      writer.upsertFile(file, language());
      writer.deleteStaleJavascriptDefinitionsForFile(file, analysis.moduleFqn());
      writer.upsertPackage(analysis.packageName(), language());
      jsWriter.upsertModule(
          file,
          analysis.packageName(),
          analysis.moduleFqn(),
          analysis.moduleName(),
          analysis.modulePath(),
          1,
          analysis.endLine());
      analysis
          .types()
          .forEach(
              type ->
                  upsertType(jsWriter, file, analysis.packageName(), analysis.modulePath(), type));
      analysis.relations().forEach(relation -> upsertRelation(jsWriter, relation));
      upsertMembers(jsWriter, file, analysis.members());
      upsertAnnotations(writer, analysis.annotations());
      upsertCalls(writer, analysis.calls(), false);
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

  private static void upsertType(
      JsGraphWriter writer,
      Path file,
      String packageName,
      String modulePath,
      io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl type) {
    if (Params.CLASS.equals(type.kind())) {
      writer.upsertClass(
          file,
          packageName,
          type.fqn(),
          type.name(),
          modulePath,
          type.framework(),
          type.isAbstract(),
          type.hasConstructor(),
          type.startLine(),
          type.endLine());
    } else if (Params.ENUM.equals(type.kind())) {
      writer.upsertEnum(
          file, packageName, type.fqn(), type.name(), modulePath, type.startLine(), type.endLine());
    } else {
      writer.upsertInterface(
          file, packageName, type.fqn(), type.name(), type.kind(), modulePath, type.framework());
    }
  }

  private static void upsertRelation(
      JsGraphWriter writer,
      io.github.ousatov.tools.memgraph.vo.analysis.module.RelationDecl relation) {
    switch (relation.kind()) {
      case Params.CLASS_EXTENDS ->
          writer.upsertExtendsClass(relation.childFqn(), relation.targetFqn());
      case Params.INTERFACE_EXTENDS ->
          writer.upsertInterfaceExtends(relation.childFqn(), relation.targetFqn());
      case Params.IMPLEMENTS -> writer.upsertImplements(relation.childFqn(), relation.targetFqn());
      default -> log.debug("Ignoring unknown JavaScript relation kind: {}", relation.kind());
    }
  }

  @Override
  protected boolean isClassDefinition(
      io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl type) {
    return Params.CLASS.equals(type.kind()) || Params.ENUM.equals(type.kind());
  }

  private static boolean isInNodeModules(Path path) {
    for (Path part : path) {
      if (Const.Files.NODE_MODULES.equals(part.toString())) {
        return true;
      }
    }
    return false;
  }
}
