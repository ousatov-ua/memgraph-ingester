package io.github.ousatov.tools.memgraph.exe.smoke;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.vo.analysis.JsAnalysis;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import io.github.ousatov.tools.memgraph.vo.analysis.module.AnnotationDecl;
import io.github.ousatov.tools.memgraph.vo.analysis.module.CallDecl;
import io.github.ousatov.tools.memgraph.vo.analysis.module.MemberDecl;
import io.github.ousatov.tools.memgraph.vo.analysis.module.RelationDecl;
import io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the JavaScript / TypeScript smoke-check assertion helpers.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings("java:S5443")
class JsRuntimeSmokeCheckTest {

  @Test
  void displayMetadataExposedForLogging() {
    JsRuntimeSmokeCheck check =
        new JsRuntimeSmokeCheck(Path.of("/tmp/cache"), "20.10.0", "5.6.3", RuntimeMode.MANAGED);

    assertEquals("JavaScript parser", check.displayName());
    assertTrue(check.tempDirPrefix().startsWith("memgraph-ingester-js-"));
    assertEquals(Path.of("/tmp/cache"), check.cacheRoot());
  }

  @Test
  void assertDefaultClassPassesWhenAnonymousDefaultClassExists() {
    JsAnalysis analysis =
        analysis(
            List.of(typeDecl("class", "default", true)),
            List.of(),
            List.of(member("method", "constructor", "<init>")),
            List.of());

    assertDoesNotThrow(() -> JsRuntimeSmokeCheck.assertDefaultClass(analysis));
  }

  @Test
  void assertDefaultClassFailsWithoutDefaultClass() {
    JsAnalysis analysis =
        analysis(
            List.of(typeDecl("class", "Other", true)),
            List.of(),
            List.of(member("method", "constructor", "<init>")),
            List.of());

    ProcessingException ex =
        assertThrows(
            ProcessingException.class, () -> JsRuntimeSmokeCheck.assertDefaultClass(analysis));
    assertTrue(ex.getMessage().contains("Default anonymous class"));
  }

  @Test
  void assertDefaultClassFailsWithoutConstructorMember() {
    JsAnalysis analysis =
        analysis(
            List.of(typeDecl("class", "default", true)),
            List.of(),
            List.of(member("method", "function", "default")),
            List.of());

    assertThrows(ProcessingException.class, () -> JsRuntimeSmokeCheck.assertDefaultClass(analysis));
  }

  @Test
  void assertDefaultFunctionPassesWhenDefaultFunctionMemberExists() {
    JsAnalysis analysis =
        analysis(List.of(), List.of(), List.of(member("method", "function", "default")), List.of());

    assertDoesNotThrow(() -> JsRuntimeSmokeCheck.assertDefaultFunction(analysis));
  }

  @Test
  void assertDefaultFunctionFailsWithoutDefaultMember() {
    JsAnalysis analysis =
        analysis(List.of(), List.of(), List.of(member("method", "function", "named")), List.of());

    ProcessingException ex =
        assertThrows(
            ProcessingException.class, () -> JsRuntimeSmokeCheck.assertDefaultFunction(analysis));
    assertTrue(ex.getMessage().contains("Default anonymous function"));
  }

  @Test
  void assertDistinctModuleFqnsPassesForDifferentModuleFqns() {
    JsAnalysis a = withModuleFqn("js.a");
    JsAnalysis b = withModuleFqn("js.b");

    assertDoesNotThrow(() -> JsRuntimeSmokeCheck.assertDistinctModuleFqns(a, b));
    assertDoesNotThrow(() -> JsRuntimeSmokeCheck.assertDistinctModuleFqns(a, b, "custom"));
  }

  @Test
  void assertDistinctModuleFqnsFailsForIdenticalModuleFqns() {
    JsAnalysis a = withModuleFqn("js.same");
    JsAnalysis b = withModuleFqn("js.same");

    ProcessingException defaultEx =
        assertThrows(
            ProcessingException.class, () -> JsRuntimeSmokeCheck.assertDistinctModuleFqns(a, b));
    assertTrue(defaultEx.getMessage().contains("must be distinct"));
    ProcessingException customEx =
        assertThrows(
            ProcessingException.class,
            () -> JsRuntimeSmokeCheck.assertDistinctModuleFqns(a, b, "custom"));
    assertTrue(customEx.getMessage().contains("custom"));
  }

  @Test
  void assertConstructorCallPassesForServiceConstructor() {
    JsAnalysis analysis =
        analysis(
            List.of(),
            List.of(),
            List.of(),
            List.of(callDecl("foo.make()", "foo.Service.<init>(any)")));

    assertDoesNotThrow(() -> JsRuntimeSmokeCheck.assertConstructorCall(analysis));
  }

  @Test
  void assertConstructorCallFailsWithoutMatchingCall() {
    JsAnalysis analysis =
        analysis(
            List.of(), List.of(), List.of(), List.of(callDecl("foo.make()", "foo.Other.run()")));

    assertThrows(
        ProcessingException.class, () -> JsRuntimeSmokeCheck.assertConstructorCall(analysis));
  }

  @Test
  void assertTsconfigPathAliasPassesForResolvedAlias() {
    JsAnalysis analysis =
        analysis(
            List.of(),
            List.of(
                relationDecl(
                    "classExtends",
                    "anything.alias$2d$consumer$2e$ts.Derived",
                    "js.src.app.specific$2e$ts.Base")),
            List.of(),
            List.of());

    assertDoesNotThrow(() -> JsRuntimeSmokeCheck.assertTsconfigPathAlias(analysis));
  }

  @Test
  void assertTsconfigPathAliasFailsWhenAliasResolvesToWrongTarget() {
    JsAnalysis analysis =
        analysis(
            List.of(),
            List.of(
                relationDecl(
                    "classExtends",
                    "anything.alias$2d$consumer$2e$ts.Derived",
                    "js.src.shared.specific$2e$ts.Base")),
            List.of(),
            List.of());

    assertThrows(
        ProcessingException.class, () -> JsRuntimeSmokeCheck.assertTsconfigPathAlias(analysis));
  }

  private static JsAnalysis withModuleFqn(String moduleFqn) {
    return new JsAnalysis(
        moduleFqn, moduleFqn, moduleFqn, moduleFqn, 1, 1, List.of(), List.of(), List.of(),
        List.of(), List.of());
  }

  private static JsAnalysis analysis(
      List<TypeDecl> types,
      List<RelationDecl> relations,
      List<MemberDecl> members,
      List<CallDecl> calls) {
    return new JsAnalysis(
        "js.module",
        "module",
        "module",
        "js",
        1,
        1,
        types,
        relations,
        members,
        List.<AnnotationDecl>of(),
        calls);
  }

  private static TypeDecl typeDecl(String kind, String name, boolean hasConstructor) {
    return new TypeDecl(kind, "js.module." + name, name, "", hasConstructor, false, 1, 1);
  }

  private static MemberDecl member(String memberType, String kind, String name) {
    return new MemberDecl("js.module." + name, memberType, kind, name, name, "", false, 1, 1);
  }

  private static RelationDecl relationDecl(String kind, String childFqn, String targetFqn) {
    return new RelationDecl(kind, childFqn, targetFqn);
  }

  private static CallDecl callDecl(String callerSignature, String calleeSignature) {
    return new CallDecl(callerSignature, calleeSignature, "", "");
  }
}
