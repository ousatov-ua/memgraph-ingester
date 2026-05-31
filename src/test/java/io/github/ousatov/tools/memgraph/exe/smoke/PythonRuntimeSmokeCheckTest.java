package io.github.ousatov.tools.memgraph.exe.smoke;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.vo.analysis.PythonAnalysis;
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
 * Unit tests for the Python smoke-check assertion helpers.
 *
 * @author Oleksii Usatov
 */
class PythonRuntimeSmokeCheckTest {

  @Test
  void displayMetadataExposedForLogging() {
    PythonRuntimeSmokeCheck check =
        new PythonRuntimeSmokeCheck(
            Path.of("/tmp/python-cache"), "3.12.0", "20240107", RuntimeMode.MANAGED);

    assertEquals("Python parser", check.displayName());
    assertTrue(check.tempDirPrefix().startsWith("memgraph-ingester-python-"));
    assertEquals(Path.of("/tmp/python-cache"), check.cacheRoot());
  }

  @Test
  void assertPythonExtendsPassesForExpectedRelation() {
    PythonAnalysis analysis =
        analysis(
            List.of(
                relation("classExtends", "python.service$2e$py.Service", "python.base$2e$py.Base")),
            List.of());

    assertDoesNotThrow(() -> PythonRuntimeSmokeCheck.assertPythonExtends(analysis));
  }

  @Test
  void assertPythonExtendsFailsWhenRelationMissing() {
    PythonAnalysis analysis =
        analysis(
            List.of(relation("classExtends", "python.other.Service", "python.other.Base")),
            List.of());

    ProcessingException ex =
        assertThrows(
            ProcessingException.class, () -> PythonRuntimeSmokeCheck.assertPythonExtends(analysis));
    assertTrue(ex.getMessage().contains("class inheritance"));
  }

  @Test
  void assertPythonCallPassesForServiceRunCallingHelper() {
    PythonAnalysis analysis =
        analysis(
            List.of(),
            List.of(call("python.service$2e$py.Service.run()", "python.service$2e$py.helper()")));

    assertDoesNotThrow(() -> PythonRuntimeSmokeCheck.assertPythonCall(analysis));
  }

  @Test
  void assertPythonCallFailsWhenCallMissing() {
    PythonAnalysis analysis = analysis(List.of(), List.of(call("foo()", "bar()")));

    ProcessingException ex =
        assertThrows(
            ProcessingException.class, () -> PythonRuntimeSmokeCheck.assertPythonCall(analysis));
    assertTrue(ex.getMessage().contains("function call"));
  }

  private static PythonAnalysis analysis(List<RelationDecl> relations, List<CallDecl> calls) {
    return new PythonAnalysis(
        "python.module",
        "module",
        "module",
        "python",
        1,
        1,
        List.<TypeDecl>of(),
        relations,
        List.<MemberDecl>of(),
        List.<AnnotationDecl>of(),
        calls);
  }

  private static RelationDecl relation(String kind, String childFqn, String targetFqn) {
    return new RelationDecl(kind, childFqn, targetFqn);
  }

  private static CallDecl call(String callerSignature, String calleeSignature) {
    return new CallDecl(callerSignature, calleeSignature, "", "");
  }
}
