package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalysis;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalyzer;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.AnnotationWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.CallWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.FieldWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.PendingCallWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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
public final class PythonLanguageAdapter implements LanguageAdapter<PythonAnalysis> {

  private static final Logger log = LoggerFactory.getLogger(PythonLanguageAdapter.class);
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of("__pycache__", ".venv", "venv", ".tox", ".nox", "site-packages", "build", "dist");

  private final PythonAnalyzer analyzer;

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
    return !isInSkippedDirectory(file) && (lower.endsWith(".py") || lower.endsWith(".pyi"));
  }

  @Override
  public boolean shouldVisitDirectory(Path directory) {
    return !isInSkippedDirectory(directory);
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
  public SourceFileDefinitions collectDefinitions(PythonAnalysis analysis) {
    return buildDefinitions(analysis);
  }

  @Override
  public boolean write(GraphWriter writer, Path file, PythonAnalysis analysis) {
    try {
      writer.upsertFile(file, language());
      writer.deleteStalePythonDefinitionsForFile(file, analysis.moduleFqn());
      writer.upsertPackage(analysis.packageName(), language());
      writer.upsertPythonModule(
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
                  writer.upsertPythonClass(
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
                  writer.upsertPythonExtendsClass(relation.childFqn(), relation.targetFqn());
                }
              });
      upsertMembers(writer, file, analysis.members());
      upsertAnnotations(writer, analysis.annotations());
      upsertCalls(writer, analysis.calls());
      return true;
    } catch (RuntimeException e) {
      if (GraphWriter.isRetryable(e)) {
        throw e;
      }
      log.warn("Failed to ingest {}: {}", file, e.getMessage());
      return false;
    }
  }

  private static SourceFileDefinitions buildDefinitions(PythonAnalysis analysis) {
    Set<String> classFqns = new LinkedHashSet<>();
    Set<String> methodSignatures = new LinkedHashSet<>();
    Set<String> fieldFqns = new LinkedHashSet<>();

    classFqns.add(analysis.moduleFqn());
    methodSignatures.add(analysis.moduleFqn() + "." + Labels.INIT + "()");
    analysis
        .types()
        .forEach(
            type -> {
              if (Params.CLASS.equals(type.kind())) {
                classFqns.add(type.fqn());
                if (!type.hasConstructor()) {
                  methodSignatures.add(type.fqn() + "." + Labels.INIT + "()");
                }
              }
            });
    analysis
        .members()
        .forEach(
            member -> {
              if (Params.METHOD.equals(member.memberType())) {
                methodSignatures.add(member.key());
              } else {
                fieldFqns.add(member.key());
              }
            });
    return SourceFileDefinitions.of(classFqns, Set.of(), Set.of(), methodSignatures, fieldFqns);
  }

  private static void upsertMembers(
      GraphWriter writer, Path file, Collection<PythonAnalysis.MemberDecl> members) {
    List<FieldWrite> fields = new ArrayList<>();
    List<Method> methods = new ArrayList<>();
    for (PythonAnalysis.MemberDecl member : members) {
      if (Params.METHOD.equals(member.memberType())) {
        methods.add(
            new Method(
                member.ownerFqn(),
                member.key(),
                member.name(),
                member.dataType(),
                member.isStatic(),
                "",
                member.startLine(),
                member.endLine(),
                false,
                SourceLanguage.PYTHON.graphName(),
                member.kind()));
      } else {
        fields.add(
            new FieldWrite(
                member.ownerFqn(),
                member.key(),
                member.name(),
                member.dataType(),
                member.isStatic(),
                "",
                SourceLanguage.PYTHON.graphName(),
                member.kind()));
      }
    }
    writer.upsertPythonMembers(file, fields, methods);
  }

  private static void upsertAnnotations(
      GraphWriter writer, Collection<PythonAnalysis.AnnotationDecl> annotations) {
    List<AnnotationWrite> ownerAnnotations = new ArrayList<>();
    List<AnnotationWrite> methodAnnotations = new ArrayList<>();
    for (PythonAnalysis.AnnotationDecl annotation : annotations) {
      AnnotationWrite write =
          new AnnotationWrite(
              annotation.ownerKey(),
              annotation.fqn(),
              annotation.name(),
              SourceLanguage.PYTHON.graphName(),
              Params.DECORATOR);
      if (Params.SIG.equals(annotation.ownerKind())) {
        methodAnnotations.add(write);
      } else {
        ownerAnnotations.add(write);
      }
    }
    writer.upsertAnnotationReferencesByFqn(ownerAnnotations);
    writer.upsertAnnotationReferencesBySig(methodAnnotations);
  }

  private static void upsertCalls(GraphWriter writer, Collection<PythonAnalysis.CallDecl> calls) {
    List<CallWrite> resolvedCalls = new ArrayList<>();
    List<PendingCallWrite> pendingCalls = new ArrayList<>();
    for (PythonAnalysis.CallDecl call : calls) {
      if (!call.calleeSignature().isBlank()) {
        resolvedCalls.add(new CallWrite(call.callerSignature(), call.calleeSignature()));
      } else if (!call.calleeOwnerFqn().isBlank() && !call.calleeName().isBlank()) {
        pendingCalls.add(
            new PendingCallWrite(call.callerSignature(), call.calleeOwnerFqn(), call.calleeName()));
      }
    }
    writer.upsertCalls(resolvedCalls);
    writer.upsertPendingCallsByName(pendingCalls);
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
