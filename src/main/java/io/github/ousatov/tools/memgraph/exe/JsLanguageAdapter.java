package io.github.ousatov.tools.memgraph.exe;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Node/TypeScript-backed adapter for JavaScript and TypeScript source structure.
 *
 * @author Oleksii Usatov
 */
public final class JsLanguageAdapter implements LanguageAdapter {

  private static final Logger log = LoggerFactory.getLogger(JsLanguageAdapter.class);
  private static final Set<String> EXTENSIONS =
      Set.of(".js", ".jsx", ".ts", ".tsx", ".mts", ".cts", ".mjs", ".cjs");

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
    String lower = path.toLowerCase(Locale.ROOT);
    if (isInNodeModules(file)) {
      return false;
    }
    return EXTENSIONS.stream().anyMatch(lower::endsWith);
  }

  @Override
  public boolean shouldVisitDirectory(Path directory) {
    return !isInNodeModules(directory);
  }

  @Override
  public boolean ingestFile(GraphWriter writer, Path file) {
    try {
      JsAnalysis analysis = analyzer.analyze(file);
      writer.deleteStaleDefinitionsForFile(file, collectDefinitions(analysis));
      writer.upsertFile(file, language());
      writer.deleteStaleJavascriptDefinitionsForFile(file, analysis.moduleFqn());
      writer.upsertPackage(analysis.packageName(), language());
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
      analysis.relations().forEach(relation -> upsertRelation(writer, relation));
      analysis.members().forEach(member -> upsertMember(writer, file, member));
      analysis.annotations().forEach(annotation -> upsertAnnotation(writer, annotation));
      analysis.calls().forEach(call -> upsertCall(writer, call));
      return true;
    } catch (RuntimeException e) {
      if (GraphWriter.isRetryable(e)) {
        throw e;
      }
      log.warn("Failed to ingest {}: {}", file, e.getMessage());
      return false;
    }
  }

  private static SourceFileDefinitions collectDefinitions(JsAnalysis analysis) {
    Set<String> classFqns = new LinkedHashSet<>();
    Set<String> interfaceFqns = new LinkedHashSet<>();
    Set<String> methodSignatures = new LinkedHashSet<>();
    Set<String> fieldFqns = new LinkedHashSet<>();

    classFqns.add(analysis.moduleFqn());
    methodSignatures.add(analysis.moduleFqn() + "." + Labels.INIT + "()");
    analysis
        .types()
        .forEach(
            type -> {
              if ("class".equals(type.kind()) || "enum".equals(type.kind())) {
                classFqns.add(type.fqn());
                if ("class".equals(type.kind()) && !type.hasConstructor()) {
                  methodSignatures.add(type.fqn() + "." + Labels.INIT + "()");
                }
              } else {
                interfaceFqns.add(type.fqn());
              }
            });
    analysis
        .members()
        .forEach(
            member -> {
              if ("method".equals(member.memberType())) {
                methodSignatures.add(member.key());
              } else {
                fieldFqns.add(member.key());
              }
            });
    return SourceFileDefinitions.of(
        classFqns, interfaceFqns, Set.of(), methodSignatures, fieldFqns);
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
          type.isAbstract(),
          type.hasConstructor(),
          type.startLine(),
          type.endLine());
    } else if ("enum".equals(type.kind())) {
      writer.upsertJavascriptEnum(
          file, packageName, type.fqn(), type.name(), modulePath, type.startLine(), type.endLine());
    } else {
      writer.upsertJavascriptInterface(
          file, packageName, type.fqn(), type.name(), type.kind(), modulePath, type.framework());
    }
  }

  private static void upsertRelation(GraphWriter writer, JsAnalysis.RelationDecl relation) {
    switch (relation.kind()) {
      case "classExtends" ->
          writer.upsertJavascriptExtendsClass(relation.childFqn(), relation.targetFqn());
      case "interfaceExtends" ->
          writer.upsertJavascriptInterfaceExtends(relation.childFqn(), relation.targetFqn());
      case "implements" ->
          writer.upsertJavascriptImplements(relation.childFqn(), relation.targetFqn());
      default -> log.debug("Ignoring unknown JavaScript relation kind: {}", relation.kind());
    }
  }

  private static void upsertMember(GraphWriter writer, Path file, JsAnalysis.MemberDecl member) {
    if ("method".equals(member.memberType())) {
      writer.upsertJavascriptMethod(
          file,
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
          file,
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
          annotation.ownerKey(),
          annotation.fqn(),
          annotation.name(),
          SourceLanguage.JAVASCRIPT.graphName(),
          "decorator");
    } else {
      writer.upsertAnnotationReferenceByFqn(
          annotation.ownerKey(),
          annotation.fqn(),
          annotation.name(),
          SourceLanguage.JAVASCRIPT.graphName(),
          "decorator");
    }
  }

  private static void upsertCall(GraphWriter writer, JsAnalysis.CallDecl call) {
    if (!call.calleeSignature().isBlank()) {
      writer.upsertCall(call.callerSignature(), call.calleeSignature());
      return;
    }
    writer.upsertPendingCallByName(
        call.callerSignature(), call.calleeOwnerFqn(), call.calleeName());
  }

  private static boolean isInNodeModules(Path path) {
    for (Path part : path) {
      if ("node_modules".equals(part.toString())) {
        return true;
      }
    }
    return false;
  }
}
