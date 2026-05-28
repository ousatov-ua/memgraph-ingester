package io.github.ousatov.tools.memgraph.exe.writer;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.exe.analyze.JavaTypeNames;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.CallWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.PendingCallWrite;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes CALLS relationships discovered inside method and constructor bodies.
 *
 * <p>Resolution is best-effort: fully resolved callees are linked by signature, while unresolved
 * local or imported calls fall back to name matching within the inferred owner type.
 *
 * @author Oleksii Usatov
 */
final class CallEdgeWriter {

  private final GraphNodeWriter nodes;

  CallEdgeWriter(GraphNodeWriter nodes) {
    this.nodes = nodes;
  }

  /**
   * Finds all call-like expressions inside {@code bodyNode}, resolves each callee, and writes a
   * {@code CALLS} edge. Handles regular method calls, method references, constructor invocations
   * ({@code new X(...)}), constructor delegation ({@code this(...)} / {@code super(...)}), and
   * constructor references ({@code Type::new}).
   */
  void upsert(String callerSig, String ownerFqn, Node bodyNode) {
    List<CallWrite> resolvedCalls = new ArrayList<>();
    List<PendingCallWrite> nameCalls = new ArrayList<>();
    bodyNode
        .findAll(MethodCallExpr.class)
        .forEach(call -> upsertMethodCallEdge(resolvedCalls, nameCalls, callerSig, ownerFqn, call));

    bodyNode
        .findAll(MethodReferenceExpr.class)
        .forEach(
            ref -> upsertMethodReferenceEdge(resolvedCalls, nameCalls, callerSig, ownerFqn, ref));

    bodyNode
        .findAll(ObjectCreationExpr.class)
        .forEach(
            creation -> upsertObjectCreationEdge(resolvedCalls, nameCalls, callerSig, creation));

    bodyNode
        .findAll(ExplicitConstructorInvocationStmt.class)
        .forEach(
            stmt -> upsertExplicitCtorEdge(resolvedCalls, nameCalls, callerSig, ownerFqn, stmt));

    nodes.upsertCalls(resolvedCalls);
    nodes.upsertCallsByName(nameCalls);
  }

  private void upsertMethodCallEdge(
      List<CallWrite> resolvedCalls,
      List<PendingCallWrite> pendingCalls,
      String callerSig,
      String ownerFqn,
      MethodCallExpr call) {
    try {
      ResolvedMethodDeclaration resolved = call.resolve();
      upsertResolvedCall(resolvedCalls, callerSig, resolved);
    } catch (Exception _) {
      upsertMethodCallFallback(pendingCalls, callerSig, ownerFqn, call);
    }
  }

  private void upsertMethodCallFallback(
      List<PendingCallWrite> pendingCalls, String callerSig, String ownerFqn, MethodCallExpr call) {
    if (call.getScope().isEmpty()) {
      upsertCallByName(pendingCalls, callerSig, ownerFqn, call.getNameAsString());
      return;
    }
    JavaTypeNames.resolveScopeTypeFqn(call)
        .ifPresent(
            scopeFqn ->
                upsertCallByName(pendingCalls, callerSig, scopeFqn, call.getNameAsString()));
  }

  /**
   * Resolves a method reference ({@code Type::method}) and writes a {@code CALLS} edge. Falls back
   * to name-based matching when the scope type matches the owning class. Constructor references
   * ({@code Type::new}) are dispatched to {@link #upsertConstructorReferenceEdge}.
   */
  private void upsertMethodReferenceEdge(
      List<CallWrite> resolvedCalls,
      List<PendingCallWrite> pendingCalls,
      String callerSig,
      String ownerFqn,
      MethodReferenceExpr ref) {
    String identifier = ref.getIdentifier();
    if ("new".equals(identifier)) {
      upsertConstructorReferenceEdge(pendingCalls, callerSig, ref);
      return;
    }
    try {
      upsertResolvedCall(resolvedCalls, callerSig, ref.resolve());
    } catch (Exception _) {
      var scope = ref.getScope();
      if (scope.isTypeExpr()
          && scope.asTypeExpr().getType().isClassOrInterfaceType()
          && scope
              .asTypeExpr()
              .getType()
              .asClassOrInterfaceType()
              .getNameAsString()
              .equals(JavaTypeNames.nameFromFqn(ownerFqn))) {
        upsertCallByName(pendingCalls, callerSig, ownerFqn, identifier);
      }
    }
  }

  /**
   * Writes a {@code CALLS} edge for a constructor invocation ({@code new X(...)}). Tries full
   * resolution first; falls back to type-inference plus name-based matching with {@code <init>}.
   */
  private void upsertObjectCreationEdge(
      List<CallWrite> resolvedCalls,
      List<PendingCallWrite> pendingCalls,
      String callerSig,
      ObjectCreationExpr creation) {
    try {
      var resolvedCtor = creation.resolve();
      upsertResolvedConstructorCall(
          resolvedCalls,
          callerSig,
          resolvedCtor.declaringType().getQualifiedName(),
          resolvedCtor.getQualifiedSignature());
    } catch (Exception _) {
      JavaTypeNames.resolveOrInferFqn(creation.getType())
          .ifPresent(fqn -> upsertCallByName(pendingCalls, callerSig, fqn, Labels.INIT));
    }
  }

  /**
   * Writes a {@code CALLS} edge for an explicit constructor delegation ({@code this(...)} or {@code
   * super(...)}). Tries full resolution first; for unresolved {@code this(...)}, falls back to
   * name-based matching within {@code ownerFqn}.
   */
  private void upsertExplicitCtorEdge(
      List<CallWrite> resolvedCalls,
      List<PendingCallWrite> pendingCalls,
      String callerSig,
      String ownerFqn,
      ExplicitConstructorInvocationStmt stmt) {
    try {
      var resolvedCtor = stmt.resolve();
      upsertResolvedConstructorCall(
          resolvedCalls,
          callerSig,
          resolvedCtor.declaringType().getQualifiedName(),
          resolvedCtor.getQualifiedSignature());
    } catch (Exception _) {
      if (stmt.isThis()) {
        upsertCallByName(pendingCalls, callerSig, ownerFqn, Labels.INIT);
      }
    }
  }

  /**
   * Writes a {@code CALLS} edge for a constructor reference ({@code Type::new}). Resolves the scope
   * type and uses name-based matching with {@code <init>}.
   */
  private void upsertConstructorReferenceEdge(
      List<PendingCallWrite> pendingCalls, String callerSig, MethodReferenceExpr ref) {
    var scope = ref.getScope();
    if (scope.isTypeExpr() && scope.asTypeExpr().getType().isClassOrInterfaceType()) {
      ClassOrInterfaceType type = scope.asTypeExpr().getType().asClassOrInterfaceType();
      JavaTypeNames.resolveOrInferFqn(type)
          .ifPresent(fqn -> upsertCallByName(pendingCalls, callerSig, fqn, Labels.INIT));
    }
  }

  private void upsertResolvedCall(
      List<CallWrite> resolvedCalls, String callerSig, ResolvedMethodDeclaration resolved) {
    upsertCall(resolvedCalls, callerSig, JavaTypeNames.buildResolvedMethodSignature(resolved));
  }

  private void upsertResolvedConstructorCall(
      List<CallWrite> resolvedCalls,
      String callerSig,
      String declaringTypeFqn,
      String qualifiedSignature) {
    String typeFqn = JavaTypeNames.normalizeNestedFqn(declaringTypeFqn);
    upsertCall(
        resolvedCalls, callerSig, JavaTypeNames.buildInitCallSig(typeFqn, qualifiedSignature));
  }

  private void upsertCall(List<CallWrite> resolvedCalls, String callerSig, String calleeSig) {
    resolvedCalls.add(new CallWrite(callerSig, calleeSig));
  }

  private void upsertCallByName(
      List<PendingCallWrite> pendingCalls, String callerSig, String ownerFqn, String calleeName) {
    pendingCalls.add(new PendingCallWrite(callerSig, ownerFqn, calleeName));
  }
}
