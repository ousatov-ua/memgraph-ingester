package io.github.ousatov.tools.memgraph.exe.analyze;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Resolves and formats Java type names and method signatures for graph identity.
 *
 * @author Oleksii Usatov
 */
public final class JavaTypeNames {

  private JavaTypeNames() {

    // Utility class.
  }

  /**
   * Resolves a class/interface reference to its FQN. Returns empty for unresolvable types (e.g.
   * generics, missing classpath entries).
   */
  public static Optional<String> resolveQualifiedName(ClassOrInterfaceType type) {
    try {
      ResolvedReferenceType resolved = type.resolve().asReferenceType();
      return resolved
          .getTypeDeclaration()
          .map(ResolvedTypeDeclaration::getQualifiedName)
          .map(JavaTypeNames::normalizeNestedFqn);
    } catch (Exception _) {
      return Optional.empty();
    }
  }

  /**
   * Builds the method signature using symbol resolution when available, falling back to
   * per-parameter resolution.
   */
  public static String buildSignature(String ownerFqn, MethodDeclaration method) {
    try {
      return buildMethodSignature(
          ownerFqn, method.getNameAsString(), method.resolve().getQualifiedSignature());
    } catch (Exception _) {
      String params =
          method.getParameters().stream()
              .map(JavaTypeNames::resolveParamType)
              .collect(Collectors.joining(", "));
      return ownerFqn + "." + method.getNameAsString() + "(" + params + ")";
    }
  }

  /** Builds {@code ownerFqn.methodName(params)} from a resolved qualified signature. */
  public static String buildMethodSignature(
      String ownerFqn, String methodName, String qualifiedSignature) {
    int parenIdx = qualifiedSignature.indexOf('(');
    return ownerFqn + "." + methodName + qualifiedSignature.substring(parenIdx);
  }

  /** Builds a graph method signature from a resolved JavaParser method declaration. */
  public static String buildResolvedMethodSignature(ResolvedMethodDeclaration resolved) {
    return buildMethodSignature(
        normalizeNestedFqn(resolved.declaringType().getQualifiedName()),
        resolved.getName(),
        resolved.getQualifiedSignature());
  }

  /** Builds the constructor signature using the graph's {@code <init>} convention. */
  public static String buildConstructorSignature(String ownerFqn, ConstructorDeclaration ctor) {
    try {
      return buildInitCallSig(ownerFqn, ctor.resolve().getQualifiedSignature());
    } catch (Exception _) {
      String params =
          ctor.getParameters().stream()
              .map(JavaTypeNames::resolveParamType)
              .collect(Collectors.joining(", "));
      return ownerFqn + "." + Labels.INIT + "(" + params + ")";
    }
  }

  /** Converts a resolved constructor's qualified signature to {@code ownerFqn.<init>(params)}. */
  public static String buildInitCallSig(String ownerFqn, String qualifiedSignature) {
    int parenIdx = qualifiedSignature.indexOf('(');
    String params = qualifiedSignature.substring(parenIdx + 1, qualifiedSignature.length() - 1);
    return ownerFqn + "." + Labels.INIT + "(" + params + ")";
  }

  /** Resolves a single parameter type, falling back to the source-level type name. */
  public static String resolveParamType(Parameter parameter) {
    try {
      return parameter.getType().resolve().describe();
    } catch (Exception _) {
      return parameter.getType().asString();
    }
  }

  /** Constructs a fully qualified name from {@code pkg} and {@code simpleName}. */
  public static String buildFqn(String pkg, String simpleName) {
    return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
  }

  /** Extracts the simple name from a fully qualified name. */
  public static String nameFromFqn(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  /** Extracts the package name from a fully qualified name. */
  public static String packageFromFqn(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? "" : fqn.substring(0, dot);
  }

  /** Converts dot-separated nested class FQNs to the graph's {@code $}-separated convention. */
  public static String normalizeNestedFqn(String fqn) {
    if (fqn == null || !fqn.contains(".")) {
      return fqn;
    }
    String[] parts = fqn.split("\\.");
    StringBuilder sb = new StringBuilder(parts[0]);
    boolean seenClass = !parts[0].isEmpty() && Character.isUpperCase(parts[0].charAt(0));
    for (int i = 1; i < parts.length; i++) {
      boolean isUpperStart = !parts[i].isEmpty() && Character.isUpperCase(parts[i].charAt(0));
      sb.append(seenClass && isUpperStart ? '$' : '.');
      if (isUpperStart) {
        seenClass = true;
      }
      sb.append(parts[i]);
    }
    return sb.toString();
  }

  /** Resolves a type to its FQN, falling back to the source-level type name. */
  public static String resolveType(com.github.javaparser.ast.type.Type type) {
    try {
      return type.resolve().describe();
    } catch (Exception _) {
      return type.asString();
    }
  }

  /** Resolves a type to its FQN via the symbol solver, falling back to import-based inference. */
  public static Optional<String> resolveOrInferFqn(ClassOrInterfaceType type) {
    Optional<String> resolved = resolveQualifiedName(type);
    if (resolved.isPresent()) {
      return resolved;
    }
    return Optional.ofNullable(inferFqnFromImports(type))
        .filter(inferred -> !inferred.equals(type.getNameAsString()));
  }

  /** Resolves {@code type} and invokes {@code action} with the best available FQN. */
  public static void withResolvedType(ClassOrInterfaceType type, Consumer<String> action) {
    Optional<String> resolved = resolveQualifiedName(type);
    resolved.or(() -> Optional.ofNullable(inferFqnFromImports(type))).ifPresent(action);
  }

  /** Attempts to determine the FQN of the type targeted by a scoped method call. */
  public static Optional<String> resolveScopeTypeFqn(MethodCallExpr call) {
    var scope = call.getScope().orElse(null);
    if (scope == null) {
      return Optional.empty();
    }
    try {
      return scope
          .calculateResolvedType()
          .asReferenceType()
          .getTypeDeclaration()
          .map(ResolvedTypeDeclaration::getQualifiedName)
          .map(JavaTypeNames::normalizeNestedFqn);
    } catch (Exception _) {
      // Expression-level resolution failed; try import inference for static calls.
    }
    if (scope.isNameExpr()) {
      String name = scope.asNameExpr().getNameAsString();
      return importedTypeFqn(call, name);
    }
    return Optional.empty();
  }

  /**
   * Infers a type FQN from compilation-unit imports, falling back to the source-level type name.
   */
  public static String inferFqnFromImports(ClassOrInterfaceType type) {
    String simpleName = type.getNameAsString();
    String result = importedTypeFqn(type, simpleName).orElseGet(() -> inferNestedImport(type));
    return normalizeNestedFqn(result);
  }

  private static Optional<String> importedTypeFqn(Node node, String name) {
    return node.findCompilationUnit()
        .flatMap(
            cu ->
                cu.getImports().stream()
                    .filter(imp -> !imp.isAsterisk() && !imp.isStatic())
                    .filter(imp -> imp.getName().getIdentifier().equals(name))
                    .map(imp -> imp.getNameAsString())
                    .findFirst());
  }

  private static String inferNestedImport(ClassOrInterfaceType type) {
    String fullName = type.asString();
    int firstDot = fullName.indexOf('.');
    if (firstDot < 0) {
      return fullName;
    }
    String topLevel = fullName.substring(0, firstDot);
    return importedTypeFqn(type, topLevel)
        .map(importFqn -> importFqn + fullName.substring(firstDot))
        .orElse(fullName);
  }
}
