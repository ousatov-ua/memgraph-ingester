package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.ModuleAnalysis;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.ModuleGraphWriter;
import io.github.ousatov.tools.memgraph.vo.Method;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.vo.writer.AnnotationWrite;
import io.github.ousatov.tools.memgraph.vo.writer.CallWrite;
import io.github.ousatov.tools.memgraph.vo.writer.FieldWrite;
import io.github.ousatov.tools.memgraph.vo.writer.PendingCallWrite;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared adapter behavior for module-oriented languages emitted by external analyzers.
 *
 * @author Oleksii Usatov
 */
public abstract class AbstractModuleLanguageAdapter<T extends ModuleAnalysis>
    implements LanguageAdapter<T> {

  protected final List<ParseResult<T>> parseBatchOneByOne(List<Path> files) {
    return LanguageAdapter.super.parseBatch(files);
  }

  @Override
  public final SourceFileDefinitions collectDefinitions(T analysis) {
    Set<String> classFqns = new LinkedHashSet<>();
    Set<String> interfaceFqns = new LinkedHashSet<>();
    Set<String> methodSignatures = new LinkedHashSet<>();
    Set<String> fieldFqns = new LinkedHashSet<>();

    classFqns.add(analysis.moduleFqn());
    methodSignatures.add(
        analysis.moduleFqn() + Const.Symbols.DOT + Labels.INIT + Const.Symbols.PARENS);
    analysis
        .types()
        .forEach(
            type -> {
              if (isClassDefinition(type)) {
                classFqns.add(type.fqn());
                if (hasSyntheticConstructor(type)) {
                  methodSignatures.add(
                      type.fqn() + Const.Symbols.DOT + Labels.INIT + Const.Symbols.PARENS);
                }
              } else {
                interfaceFqns.add(type.fqn());
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
    return SourceFileDefinitions.of(
        classFqns, interfaceFqns, Set.of(), methodSignatures, fieldFqns);
  }

  protected boolean isClassDefinition(
      io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl type) {
    return Params.CLASS.equals(type.kind());
  }

  protected boolean hasSyntheticConstructor(
      io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl type) {
    return Params.CLASS.equals(type.kind()) && !type.hasConstructor();
  }

  protected final void upsertMembers(
      ModuleGraphWriter writer,
      Path file,
      Collection<? extends io.github.ousatov.tools.memgraph.vo.analysis.module.MemberDecl>
          members) {
    List<FieldWrite> fields = new ArrayList<>();
    List<Method> methods = new ArrayList<>();
    for (io.github.ousatov.tools.memgraph.vo.analysis.module.MemberDecl member : members) {
      if (Params.METHOD.equals(member.memberType())) {
        methods.add(
            new Method(
                member.ownerFqn(),
                member.key(),
                member.name(),
                member.dataType(),
                member.isStatic(),
                Const.Symbols.EMPTY,
                member.startLine(),
                member.endLine(),
                false,
                language().graphName(),
                member.kind()));
      } else {
        fields.add(
            new FieldWrite(
                member.ownerFqn(),
                member.key(),
                member.name(),
                member.dataType(),
                member.isStatic(),
                Const.Symbols.EMPTY,
                language().graphName(),
                member.kind()));
      }
    }
    writer.upsertMembers(file, fields, methods);
  }

  protected final void upsertAnnotations(
      GraphWriter writer,
      Collection<? extends io.github.ousatov.tools.memgraph.vo.analysis.module.AnnotationDecl>
          annotations) {
    List<AnnotationWrite> ownerAnnotations = new ArrayList<>();
    List<AnnotationWrite> methodAnnotations = new ArrayList<>();
    for (io.github.ousatov.tools.memgraph.vo.analysis.module.AnnotationDecl annotation :
        annotations) {
      AnnotationWrite write =
          new AnnotationWrite(
              annotation.ownerKey(),
              annotation.fqn(),
              annotation.name(),
              language().graphName(),
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

  protected final void upsertCalls(
      GraphWriter writer,
      Collection<? extends io.github.ousatov.tools.memgraph.vo.analysis.module.CallDecl> calls,
      boolean skipIncompletePendingCalls) {
    List<CallWrite> resolvedCalls = new ArrayList<>();
    List<PendingCallWrite> pendingCalls = new ArrayList<>();
    for (io.github.ousatov.tools.memgraph.vo.analysis.module.CallDecl call : calls) {
      if (!call.calleeSignature().isBlank()) {
        resolvedCalls.add(new CallWrite(call.callerSignature(), call.calleeSignature()));
      } else if (!skipIncompletePendingCalls || isCompletePendingCall(call)) {
        pendingCalls.add(
            new PendingCallWrite(call.callerSignature(), call.calleeOwnerFqn(), call.calleeName()));
      }
    }
    writer.upsertCalls(resolvedCalls);
    writer.upsertPendingCallsByName(pendingCalls);
  }

  private static boolean isCompletePendingCall(
      io.github.ousatov.tools.memgraph.vo.analysis.module.CallDecl call) {
    return !call.calleeOwnerFqn().isBlank() && !call.calleeName().isBlank();
  }
}
