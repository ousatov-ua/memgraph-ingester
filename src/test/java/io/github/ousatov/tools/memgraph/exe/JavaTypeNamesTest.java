package io.github.ousatov.tools.memgraph.exe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JavaTypeNames}.
 *
 * @author Oleksii Usatov
 */
class JavaTypeNamesTest {

  @Test
  void normalizesNestedClassNamesOnlyAfterClassSegment() {
    assertEquals(
        "com.example.Outer$Inner", JavaTypeNames.normalizeNestedFqn("com.example.Outer.Inner"));
    assertEquals("java.util.Map$Entry", JavaTypeNames.normalizeNestedFqn("java.util.Map.Entry"));
    assertEquals("Outer$Inner", JavaTypeNames.normalizeNestedFqn("Outer.Inner"));
    assertEquals("simple", JavaTypeNames.normalizeNestedFqn("simple"));
    assertNull(JavaTypeNames.normalizeNestedFqn(null));
  }

  @Test
  void extractsNameAndPackageFromFqn() {
    assertEquals("Widget", JavaTypeNames.nameFromFqn("com.example.Widget"));
    assertEquals("Widget", JavaTypeNames.nameFromFqn("Widget"));
    assertEquals("com.example", JavaTypeNames.packageFromFqn("com.example.Widget"));
    assertEquals("", JavaTypeNames.packageFromFqn("Widget"));
    assertEquals("com.example.Widget", JavaTypeNames.buildFqn("com.example", "Widget"));
    assertEquals("Widget", JavaTypeNames.buildFqn("", "Widget"));
  }

  @Test
  void buildsMethodSignatureFromQualifiedSignature() {
    assertEquals(
        "com.example.Widget.work(java.lang.String, int)",
        JavaTypeNames.buildMethodSignature(
            "com.example.Widget", "work", "work(java.lang.String, int)"));
  }

  @Test
  void buildsFallbackMethodAndConstructorSignatures() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            package com.example;
            class Widget {
              Widget(String name, int count) {}
              void work(String input, long size) {}
            }
            """);
    ClassOrInterfaceDeclaration widget =
        unit.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();

    assertEquals(
        "com.example.Widget.work(String, long)",
        JavaTypeNames.buildSignature(
            "com.example.Widget", widget.getMethodsByName("work").getFirst()));
    assertEquals(
        "com.example.Widget.<init>(String, int)",
        JavaTypeNames.buildConstructorSignature(
            "com.example.Widget", widget.getConstructors().getFirst()));
  }

  @Test
  void infersImportedNestedTypeNames() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import org.junit.jupiter.api.extension.ExtensionContext;
            class UsesType {
              ExtensionContext.Store.CloseableResource resource;
            }
            """);
    ClassOrInterfaceType type =
        unit.findFirst(ClassOrInterfaceType.class, t -> t.asString().contains("CloseableResource"))
            .orElseThrow();

    assertEquals(
        "org.junit.jupiter.api.extension.ExtensionContext$Store$CloseableResource",
        JavaTypeNames.inferFqnFromImports(type));
    assertTrue(JavaTypeNames.resolveOrInferFqn(type).isPresent());
  }

  @Test
  void fallsBackToSourceNameWhenImportsDoNotMatch() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            class UsesType {
              MissingType value;
            }
            """);
    ClassOrInterfaceType type =
        unit.findFirst(ClassOrInterfaceType.class, t -> t.asString().equals("MissingType"))
            .orElseThrow();

    assertEquals("MissingType", JavaTypeNames.inferFqnFromImports(type));
    assertTrue(JavaTypeNames.resolveOrInferFqn(type).isEmpty());
  }

  @Test
  void resolvesScopedStaticCallTypeFromImports() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import com.example.Utility;
            class UsesCall {
              void call() {
                Utility.work();
              }
            }
            """);
    MethodCallExpr call =
        unit.findFirst(MethodCallExpr.class, c -> c.getNameAsString().equals("work")).orElseThrow();

    assertEquals("com.example.Utility", JavaTypeNames.resolveScopeTypeFqn(call).orElseThrow());
  }

  @Test
  void returnsEmptyForUnscopedCalls() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            class UsesCall {
              void call() {
                work();
              }
            }
            """);
    MethodCallExpr call =
        unit.findFirst(MethodCallExpr.class, c -> c.getNameAsString().equals("work")).orElseThrow();

    assertTrue(JavaTypeNames.resolveScopeTypeFqn(call).isEmpty());
  }
}
