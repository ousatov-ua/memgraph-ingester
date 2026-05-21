package io.github.ousatov.tools.memgraph.exe;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Node/TypeScript-backed adapter for JavaScript and TypeScript source structure. */
public final class JsLanguageAdapter implements LanguageAdapter {

  private static final Logger log = LoggerFactory.getLogger(JsLanguageAdapter.class);
  private static final Set<String> EXTENSIONS =
      Set.of(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs");

  private final JsAnalyzer analyzer;

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
    if (path.contains("/node_modules/") || path.endsWith(".d.ts")) {
      return false;
    }
    String lower = path.toLowerCase(Locale.ROOT);
    return EXTENSIONS.stream().anyMatch(lower::endsWith);
  }

  @Override
  public boolean ingestFile(GraphWriter writer, Path file) {
    try {
      JsAnalysis analysis = analyzer.analyze(file);
      writer.upsertFile(file, language().graphName());
      writer.upsertPackage(analysis.packageName());
      writer.upsertJavascriptModule(
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
                  upsertType(writer, file, analysis.packageName(), analysis.modulePath(), type));
      analysis.members().forEach(member -> upsertMember(writer, member));
      analysis.annotations().forEach(annotation -> upsertAnnotation(writer, annotation));
      analysis
          .calls()
          .forEach(call -> writer.upsertCall(call.callerSignature(), call.calleeSignature()));
      return true;
    } catch (ProcessingException e) {
      log.warn("Failed to ingest {}: {}", file, e.getMessage());
      return false;
    }
  }

  private static void upsertType(
      GraphWriter writer,
      Path file,
      String packageName,
      String modulePath,
      JsAnalysis.TypeDecl type) {
    if ("class".equals(type.kind())) {
      writer.upsertJavascriptClass(
          file,
          packageName,
          type.fqn(),
          type.name(),
          modulePath,
          type.framework(),
          type.startLine(),
          type.endLine());
    } else {
      writer.upsertJavascriptInterface(
          file, packageName, type.fqn(), type.name(), type.kind(), modulePath, type.framework());
    }
  }

  private static void upsertMember(GraphWriter writer, JsAnalysis.MemberDecl member) {
    if ("method".equals(member.memberType())) {
      writer.upsertJavascriptMethod(
          member.ownerFqn(),
          member.key(),
          member.name(),
          member.dataType(),
          member.isStatic(),
          member.startLine(),
          member.endLine(),
          member.kind());
    } else {
      writer.upsertJavascriptField(
          member.ownerFqn(),
          member.key(),
          member.name(),
          member.dataType(),
          member.isStatic(),
          member.kind());
    }
  }

  private static void upsertAnnotation(GraphWriter writer, JsAnalysis.AnnotationDecl annotation) {
    if ("sig".equals(annotation.ownerKind())) {
      writer.upsertAnnotationReferenceBySig(
          annotation.ownerKey(), annotation.fqn(), annotation.name());
    } else {
      writer.upsertAnnotationReferenceByFqn(
          annotation.ownerKey(), annotation.fqn(), annotation.name());
    }
  }
}
