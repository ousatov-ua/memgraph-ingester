package io.github.ousatov.tools.memgraph.exe;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.util.Map;

/**
 * Writes CALLS relationships discovered inside method and constructor bodies.
 *
 * <p>Resolution is best-effort: fully resolved callees are linked by signature, while unresolved
 * local or imported calls fall back to name matching within the inferred owner type.
 *
 * @author Oleksii Usatov
 */
final class CallEdgeWriter {

  private final CypherExecutor cypher;

  CallEdgeWriter(CypherExecutor cypher) {
    this.cypher = cypher;
  }

  /**
   * Finds all call-like expressions inside {@code bodyNode}, resolves each callee, and writes a
   * {@code CALLS} edge. Handles regular method calls, method references, constructor invocations
   * ({@code new X(...)}), constructor delegation ({@code this(...)} / {@code super(...)}), and
   * constructor references ({@code Type::new}).
   */
  void upsert(String callerSig, String ownerFqn, Node bodyNode) {
    bodyNode
        .findAll(MethodCallExpr.class)
        .forEach(call -> upsertMethodCallEdge(callerSig, ownerFqn, call));

    bodyNode
        .findAll(MethodReferenceExpr.class)
        .forEach(ref -> upsertMethodReferenceEdge(callerSig, ownerFqn, ref));

    bodyNode
        .findAll(ObjectCreationExpr.class)
        .forEach(creation -> upsertObjectCreationEdge(callerSig, creation));

    bodyNode
        .findAll(ExplicitConstructorInvocationStmt.class)
        .forEach(stmt -> upsertExplicitCtorEdge(callerSig, ownerFqn, stmt));
  }

  private void upsertMethodCallEdge(String callerSig, String ownerFqn, MethodCallExpr call) {
    try {
      ResolvedMethodDeclaration resolved = call.resolve();
      upsertResolvedCall(callerSig, resolved);
    } catch (Exception _) {
      upsertMethodCallFallback(callerSig, ownerFqn, call);
    }
  }

  private void upsertMethodCallFallback(String callerSig, String ownerFqn, MethodCallExpr call) {
    if (call.getScope().isEmpty()) {
      upsertCallByName(callerSig, ownerFqn, call.getNameAsString());
      return;
    }
    JavaTypeNames.resolveScopeTypeFqn(call)
        .ifPresent(scopeFqn -> upsertCallByName(callerSig, scopeFqn, call.getNameAsString()));
  }

  /**
   * Resolves a method reference ({@code Type::method}) and writes a {@code CALLS} edge. Falls back
   * to name-based matching when the scope type matches the owning class. Constructor references
   * ({@code Type::new}) are dispatched to {@link #upsertConstructorReferenceEdge}.
   */
  private void upsertMethodReferenceEdge(
      String callerSig, String ownerFqn, MethodReferenceExpr ref) {
    String identifier = ref.getIdentifier();
    if ("new".equals(identifier)) {
      upsertConstructorReferenceEdge(callerSig, ref);
      return;
    }
    try {
      upsertResolvedCall(callerSig, ref.resolve());
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
        upsertCallByName(callerSig, ownerFqn, identifier);
      }
    }
  }

  /**
   * Writes a {@code CALLS} edge for a constructor invocation ({@code new X(...)}). Tries full
   * resolution first; falls back to type-inference plus name-based matching with {@code <init>}.
   */
  private void upsertObjectCreationEdge(String callerSig, ObjectCreationExpr creation) {
    try {
      var resolvedCtor = creation.resolve();
      String typeFqn =
          JavaTypeNames.normalizeNestedFqn(resolvedCtor.declaringType().getQualifiedName());
      String calleeSig =
          JavaTypeNames.buildInitCallSig(typeFqn, resolvedCtor.getQualifiedSignature());
      upsertCall(callerSig, calleeSig);
    } catch (Exception _) {
      JavaTypeNames.resolveOrInferFqn(creation.getType())
          .ifPresent(fqn -> upsertCallByName(callerSig, fqn, Labels.INIT));
    }
  }

  /**
   * Writes a {@code CALLS} edge for an explicit constructor delegation ({@code this(...)} or {@code
   * super(...)}). Tries full resolution first; for unresolved {@code this(...)}, falls back to
   * name-based matching within {@code ownerFqn}.
   */
  private void upsertExplicitCtorEdge(
      String callerSig, String ownerFqn, ExplicitConstructorInvocationStmt stmt) {
    try {
      var resolvedCtor = stmt.resolve();
      String typeFqn =
          JavaTypeNames.normalizeNestedFqn(resolvedCtor.declaringType().getQualifiedName());
      String calleeSig =
          JavaTypeNames.buildInitCallSig(typeFqn, resolvedCtor.getQualifiedSignature());
      upsertCall(callerSig, calleeSig);
    } catch (Exception _) {
      if (stmt.isThis()) {
        upsertCallByName(callerSig, ownerFqn, Labels.INIT);
      }
    }
  }

  /**
   * Writes a {@code CALLS} edge for a constructor reference ({@code Type::new}). Resolves the scope
   * type and uses name-based matching with {@code <init>}.
   */
  private void upsertConstructorReferenceEdge(String callerSig, MethodReferenceExpr ref) {
    var scope = ref.getScope();
    if (scope.isTypeExpr() && scope.asTypeExpr().getType().isClassOrInterfaceType()) {
      ClassOrInterfaceType type = scope.asTypeExpr().getType().asClassOrInterfaceType();
      JavaTypeNames.resolveOrInferFqn(type)
          .ifPresent(fqn -> upsertCallByName(callerSig, fqn, Labels.INIT));
    }
  }

  private void upsertResolvedCall(String callerSig, ResolvedMethodDeclaration resolved) {
    upsertCall(callerSig, JavaTypeNames.buildResolvedMethodSignature(resolved));
  }

  private void upsertCall(String callerSig, String calleeSig) {
    cypher.run(
        Cypher.CYPHER_UPSERT_CALL, Map.of(Params.CALLER, callerSig, Params.CALLEE, calleeSig));
  }

  private void upsertCallByName(String callerSig, String ownerFqn, String calleeName) {
    cypher.run(
        Cypher.CYPHER_UPSERT_CALL_BY_NAME,
        Map.of(
            Params.CALLER, callerSig, Params.OWNER_FQN, ownerFqn, Params.CALLEE_NAME, calleeName));
  }
}
