package io.github.ousatov.tools.memgraph.exe.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
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
  void decodesUnicodeEscapesFromHelperJson() throws IOException {
    PythonAnalysis analysis =
        analyzeSource(
            "unicode.py",
            """
            def \u03bb():
                return 1
            """);

    assertTrue(
        analysis.members().stream().anyMatch(member -> "\u03bb".equals(member.name())),
        () -> "Members: " + analysis.members());
  }

  @Test
  void withSourceRootReusesExtractedHelperScript() throws ReflectiveOperationException {
    ManagedPythonRuntime runtime =
        new ManagedPythonRuntime(
            tempDir.resolve("runtime"),
            ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
            ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
            RuntimeMode.OFFLINE);
    PythonAnalyzer analyzer = new PythonAnalyzer(tempDir, runtime);

    PythonAnalyzer rebased = analyzer.withSourceRoot(tempDir.resolve("other-root"));

    assertSame(helperScript(analyzer), helperScript(rebased));
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

  @Test
  void resolvesImportsFromPythonStubFiles() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(packageDir.resolve("__init__.pyi"), "class Root: ...\n");
    Files.writeString(packageDir.resolve("util.pyi"), "class Base: ...\n");
    Path serviceFile = tempDir.resolve("service.py");
    Files.writeString(
        serviceFile,
        """
        import pkg
        from pkg.util import Base

        class Service(Base):
            pass

        class PackageService(pkg.Root):
            pass
        """);

    PythonAnalysis analysis = analyzer().analyze(serviceFile);

    assertTrue(
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    "classExtends".equals(relation.kind())
                        && "python.service$2e$py.Service".equals(relation.childFqn())
                        && "python.pkg.util$2e$pyi.Base".equals(relation.targetFqn())),
        () -> "Relations: " + analysis.relations());
    assertTrue(
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    "classExtends".equals(relation.kind())
                        && "python.service$2e$py.PackageService".equals(relation.childFqn())
                        && "python.pkg._$5f$$5f$init$5f$$5f$$2e$pyi.Root"
                            .equals(relation.targetFqn())),
        () -> "Relations: " + analysis.relations());
  }

  @Test
  void keepsImportedCapitalizedCallableAsModuleFunctionCall() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(packageDir.resolve("mod.py"), "def Factory():\n    return 1\n");
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        from pkg.mod import Factory

        def run():
            Factory()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.mod$2e$py", "Factory"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void resolvesImportedClassCallAsConstructor() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(packageDir.resolve("mod.py"), "class Factory:\n    pass\n");
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        from pkg.mod import Factory

        def run():
            Factory()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.mod$2e$py.Factory", "<init>"),
        () -> "Calls: " + analysis.calls());
    assertFalse(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.mod$2e$py", "Factory"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void resolvesModuleQualifiedClassCallAsConstructor() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(
        packageDir.resolve("mod.py"),
        """
        class Factory:
            @staticmethod
            def build():
                return 1

        def helper():
            return 1
        """);
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        import pkg.mod

        def run():
            pkg.mod.Factory()
            pkg.mod.Factory.build()
            pkg.mod.helper()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.mod$2e$py.Factory", "<init>"),
        () -> "Calls: " + analysis.calls());
    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.mod$2e$py.Factory", "build"),
        () -> "Calls: " + analysis.calls());
    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.mod$2e$py", "helper"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void resolvesPackageQualifiedSubmoduleClassCallAsConstructor() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(packageDir.resolve("__init__.py"), "");
    Files.writeString(
        packageDir.resolve("mod.py"),
        """
        class Factory:
            pass

        def helper():
            return 1
        """);
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        import pkg

        def run():
            pkg.mod.Factory()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.mod$2e$py.Factory", "<init>"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void resolvesSubmoduleCallsThroughImportedPackageQualifier() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(packageDir.resolve("__init__.py"), "");
    Files.writeString(packageDir.resolve("util.py"), "def helper():\n    return 1\n");
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        import pkg

        def run():
            pkg.util.helper()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.util$2e$py", "helper"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void bindsTopLevelPackageNameForDottedImports() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(packageDir.resolve("__init__.py"), "def helper():\n    return 1\n");
    Files.writeString(packageDir.resolve("util.py"), "def utility():\n    return 2\n");
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        import pkg.util

        def run():
            pkg.helper()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(
            analysis, "python.app$2e$py.run()", "python.pkg._$5f$$5f$init$5f$$5f$$2e$py", "helper"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void prefersImportedPackageSymbolOverSameNamedSubmodule() throws IOException {
    Path utilDir = tempDir.resolve("pkg").resolve("util");
    Files.createDirectories(utilDir);
    Files.writeString(utilDir.resolve("__init__.py"), "def helper():\n    return 1\n");
    Files.writeString(utilDir.resolve("helper.py"), "def other():\n    return 2\n");
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        from pkg.util import helper

        def run():
            helper()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(
            analysis,
            "python.app$2e$py.run()",
            "python.pkg.util._$5f$$5f$init$5f$$5f$$2e$py",
            "helper"),
        () -> "Calls: " + analysis.calls());
    assertFalse(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.util.helper$2e$py", "<init>"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void resolvesPackageReexportedImportNameBeforeSameNamedSubmodule() throws IOException {
    Path utilDir = tempDir.resolve("pkg").resolve("util");
    Files.createDirectories(utilDir);
    Files.writeString(utilDir.resolve("__init__.py"), "from .core import helper\n");
    Files.writeString(utilDir.resolve("core.py"), "def helper():\n    return 1\n");
    Files.writeString(utilDir.resolve("helper.py"), "def other():\n    return 2\n");
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        from pkg.util import helper

        def run():
            helper()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.util.core$2e$py", "helper"),
        () -> "Calls: " + analysis.calls());
    assertFalse(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.util.helper$2e$py", "<init>"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void resolvesFunctionLocalImportsForCalls() throws IOException {
    Path packageDir = tempDir.resolve("pkg");
    Files.createDirectories(packageDir);
    Files.writeString(packageDir.resolve("util.py"), "def helper():\n    return 1\n");
    Path appFile = tempDir.resolve("app.py");
    Files.writeString(
        appFile,
        """
        def run():
            import pkg.util
            pkg.util.helper()
        """);

    PythonAnalysis analysis = analyzer().analyze(appFile);

    assertTrue(
        hasCallByName(analysis, "python.app$2e$py.run()", "python.pkg.util$2e$py", "helper"),
        () -> "Calls: " + analysis.calls());
  }

  @Test
  void ignoresNestedClassSelfAssignmentsAsOuterInstanceFields() throws IOException {
    PythonAnalysis analysis =
        analyzeSource(
            "fields.py",
            """
            class Outer:
                def build(self):
                    self.name = "outer"

                    class Inner:
                        def __init__(self):
                            self.leaked = "inner"
            """);

    assertTrue(
        analysis.members().stream()
            .anyMatch(member -> "python.fields$2e$py.Outer#name".equals(member.key())),
        () -> "Members: " + analysis.members());
    assertFalse(
        analysis.members().stream()
            .anyMatch(member -> "python.fields$2e$py.Outer#leaked".equals(member.key())),
        () -> "Members: " + analysis.members());
  }

  @Test
  void stripsDecoratorCallSyntaxFromAnnotations() throws IOException {
    PythonAnalysis analysis =
        analyzeSource(
            "decorators.py",
            """
            @cache()
            class Service:
                @pkg.trace("run")
                def run(self):
                    return None
            """);

    assertTrue(
        analysis.annotations().stream().anyMatch(annotation -> "cache".equals(annotation.fqn())),
        () -> "Annotations: " + analysis.annotations());
    assertTrue(
        analysis.annotations().stream()
            .anyMatch(annotation -> "pkg.trace".equals(annotation.fqn())),
        () -> "Annotations: " + analysis.annotations());
    assertFalse(
        analysis.annotations().stream().anyMatch(annotation -> annotation.fqn().contains("(")),
        () -> "Annotations: " + analysis.annotations());
  }

  @Test
  void collectsModuleAndClassFieldsFromControlBlocks() throws IOException {
    PythonAnalysis analysis =
        analyzeSource(
            "members.py",
            """
            if True:
                module_flag: int = 1

            class Service:
                if True:
                    enabled: bool = True
                try:
                    name = "service"
                except Exception:
                    fallback = "fallback"

                def run(self):
                    if True:
                        local_value = 1
            """);

    assertTrue(hasMember(analysis, "python.members$2e$py#module_flag"));
    assertTrue(hasMember(analysis, "python.members$2e$py.Service#enabled"));
    assertTrue(hasMember(analysis, "python.members$2e$py.Service#name"));
    assertTrue(hasMember(analysis, "python.members$2e$py.Service#fallback"));
    assertFalse(
        hasMember(analysis, "python.members$2e$py.Service#local_value"),
        () -> "Members: " + analysis.members());
  }

  @Test
  void helperFailsFastWhenAstUnparseIsUnavailable() throws IOException, InterruptedException {
    assumeTrue(systemPythonAvailable(), "Python analyzer helper test requires python3");
    Path sourceFile = tempDir.resolve("app.py");
    Files.writeString(sourceFile, "@decorator\nclass Service:\n    pass\n");
    Path helperScript =
        Path.of("src/main/resources/io/github/ousatov/tools/memgraph/python/python-analyzer.py")
            .toAbsolutePath();
    String command =
        "import ast, runpy, sys; "
            + "delattr(ast, 'unparse') if hasattr(ast, 'unparse') else None; "
            + "source, root, helper = sys.argv[1:4]; "
            + "sys.argv = ['python-analyzer.py', '--file', source, '--root', root]; "
            + "runpy.run_path(helper, run_name='__main__')";

    Process process =
        new ProcessBuilder(
                ManagedPythonRuntime.systemPythonExecutable(),
                "-c",
                command,
                sourceFile.toString(),
                tempDir.toString(),
                helperScript.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    assertNotEquals(0, process.exitValue(), () -> "Output: " + output);
    assertTrue(output.contains("requires Python 3.9+"), () -> "Output: " + output);
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

  private static boolean hasMember(PythonAnalysis analysis, String key) {
    return analysis.members().stream().anyMatch(member -> key.equals(member.key()));
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

  private static Path helperScript(PythonAnalyzer analyzer) throws ReflectiveOperationException {
    Field field = PythonAnalyzer.class.getDeclaredField("helperScript");
    field.setAccessible(true);
    return (Path) field.get(analyzer);
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
