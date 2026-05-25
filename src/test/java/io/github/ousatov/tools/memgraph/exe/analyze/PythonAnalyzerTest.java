package io.github.ousatov.tools.memgraph.exe.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the Python analyzer helper.
 *
 * @author Oleksii Usatov
 */
class PythonAnalyzerTest {

  @TempDir private Path tempDir;

  @Test
  void analyzesClassesFieldsDecoratorsAndCalls() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(
        packageDir.resolve("base.py"),
        """
        class Base:
            pass
        """);
    Path serviceFile = packageDir.resolve("service.py");
    Files.writeString(
        serviceFile,
        """
        from .base import Base

        @component
        class Service(Base):
            value: int = 1

            def __init__(self):
                self.name: str = "service"

            def run(self):
                helper()

        def helper():
            return 1
        """);

    PythonAnalysis analysis = analyzer().analyze(serviceFile);

    assertEquals("python.pkg.service$2e$py", analysis.moduleFqn());
    assertTrue(
        analysis.types().stream()
            .anyMatch(
                type ->
                    "class".equals(type.kind())
                        && "Service".equals(type.name())
                        && "python.pkg.service$2e$py.Service".equals(type.fqn())));
    assertTrue(
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    "classExtends".equals(relation.kind())
                        && "python.pkg.service$2e$py.Service".equals(relation.childFqn())
                        && "python.pkg.base$2e$py.Base".equals(relation.targetFqn())));
    assertTrue(
        analysis.annotations().stream()
            .anyMatch(annotation -> "component".equals(annotation.fqn())));
    assertTrue(
        analysis.members().stream()
            .anyMatch(member -> "python.pkg.service$2e$py.Service#name".equals(member.key())));
    assertTrue(
        analysis.members().stream()
            .anyMatch(member -> "python.pkg.service$2e$py.Service.run()".equals(member.key())),
        () -> "Members: " + analysis.members());
    assertTrue(
        analysis.calls().stream()
            .anyMatch(
                call ->
                    "python.pkg.service$2e$py.Service.run()".equals(call.callerSignature())
                        && "python.pkg.service$2e$py.helper()".equals(call.calleeSignature())));
  }

  @Test
  void resolvesSelfCallsAsDeferredOwnerNameCalls() throws IOException {
    PythonAnalysis analysis =
        analyzeSource(
            "self_calls.py",
            """
            class Service:
                def run(self):
                    self.save()

                def save(self):
                    return None
            """);

    assertTrue(
        analysis.calls().stream()
            .anyMatch(
                call ->
                    "python.self$5f$calls$2e$py.Service.run()".equals(call.callerSignature())
                        && call.calleeSignature().isBlank()
                        && "python.self$5f$calls$2e$py.Service".equals(call.calleeOwnerFqn())
                        && "save".equals(call.calleeName())));
  }

  @Test
  void resolvesDottedImportCallsWithoutDuplicatingModulePath() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(
        packageDir.resolve("util.py"),
        """
        def helper():
            return 1
        """);
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        import pkg.util

        def run():
            pkg.util.helper()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.util$2e$py", "helper"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void preservesDistinctDottedImportsUnderSameTopLevelPackage() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(packageDir.resolve("util.py"), "def helper():\n    return 1\n");
    Files.writeString(packageDir.resolve("other.py"), "def helper():\n    return 2\n");
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        import pkg.util
        import pkg.other

        def run():
            pkg.util.helper()
            pkg.other.helper()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.util$2e$py", "helper"),
        () -> "Calls: " + analysis.calls());
    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.other$2e$py", "helper"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void resolvesRelativeNamespaceImportSubmoduleCalls() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(packageDir.resolve("base.py"), "def helper():\n    return 1\n");
    Path serviceFile = packageDir.resolve("service.py");
    Files.writeString(
        serviceFile,
        """
        from . import base

        def run():
            base.helper()
        """);

    PythonAnalysis analysis = analyzer().analyze(serviceFile);

    assertTrue(
        hasCallByName(
            analysis, "python.pkg.service$2e$py.run()", "python.pkg.base$2e$py", "helper"),
        () -> "Calls: " + analysis.calls());
  }

  private static boolean hasCallByName(
      PythonAnalysis analysis, String callerSignature, String calleeOwnerFqn, String calleeName) {
    return analysis.calls().stream()
        .anyMatch(
            call ->
                callerSignature.equals(call.callerSignature())
                    && call.calleeSignature().isBlank()
                    && calleeOwnerFqn.equals(call.calleeOwnerFqn())
                    && calleeName.equals(call.calleeName()));
  }

  private PythonAnalysis analyzeSource(String fileName, String source) throws IOException {
    Path sourceFile = tempDir.resolve(fileName);
    Files.writeString(sourceFile, source);
    return analyzer().analyze(sourceFile);
  }

  private PythonAnalyzer analyzer() {
    assumeTrue(systemPythonAvailable(), "Python analyzer helper test requires python3");
    return new PythonAnalyzer(
        tempDir,
        new ManagedPythonRuntime(
            tempDir.resolve("runtime"),
            ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
            ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
            RuntimeMode.SYSTEM));
  }

  private static boolean systemPythonAvailable() {
    try {
      Process process =
          new ProcessBuilder(ManagedPythonRuntime.systemPythonExecutable(), "--version")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException _) {
      return false;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
