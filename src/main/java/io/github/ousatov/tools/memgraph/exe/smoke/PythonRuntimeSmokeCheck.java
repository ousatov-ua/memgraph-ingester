package io.github.ousatov.tools.memgraph.exe.smoke;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedPythonRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalyzer;
import io.github.ousatov.tools.memgraph.vo.analysis.PythonAnalysis;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smoke check for the managed Python parser runtime.
 *
 * @author Oleksii Usatov
 */
public final class PythonRuntimeSmokeCheck extends RuntimeSmokeCheck {

  private static final Logger log = LoggerFactory.getLogger(PythonRuntimeSmokeCheck.class);

  private final Path cacheRoot;
  private final String pythonVersion;
  private final String pythonBuild;
  private final RuntimeMode runtimeMode;

  public PythonRuntimeSmokeCheck(
      Path cacheRoot, String pythonVersion, String pythonBuild, RuntimeMode runtimeMode) {
    super(log);
    this.cacheRoot = cacheRoot;
    this.pythonVersion = pythonVersion;
    this.pythonBuild = pythonBuild;
    this.runtimeMode = runtimeMode;
  }

  @Override
  protected String displayName() {
    return "Python parser";
  }

  @Override
  protected String tempDirPrefix() {
    return "memgraph-ingester-python-runtime-check-";
  }

  @Override
  protected Path cacheRoot() {
    return cacheRoot;
  }

  @Override
  protected void execute(Path tempDir) throws IOException {
    Path baseFile = tempDir.resolve("base.py");
    Path serviceFile = tempDir.resolve("service.py");
    Files.writeString(
        baseFile,
        """
        class Base:
            pass
        """);
    Files.writeString(
        serviceFile,
        """
        from base import Base

        class Service(Base):
            def run(self):
                helper()

        def helper():
            return 1
        """);

    PythonAnalyzer analyzer =
        new PythonAnalyzer(
            tempDir, new ManagedPythonRuntime(cacheRoot, pythonVersion, pythonBuild, runtimeMode));
    PythonAnalysis analysis = analyzer.analyze(serviceFile);
    assertPythonExtends(analysis);
    assertPythonCall(analysis);
  }

  static void assertPythonExtends(PythonAnalysis analysis) {
    boolean extendsFound =
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    Params.CLASS_EXTENDS.equals(relation.kind())
                        && "python.service$2e$py.Service".equals(relation.childFqn())
                        && "python.base$2e$py.Base".equals(relation.targetFqn()));
    if (!extendsFound) {
      throw new ProcessingException("Python class inheritance was not parsed correctly");
    }
  }

  static void assertPythonCall(PythonAnalysis analysis) {
    boolean callFound =
        analysis.calls().stream()
            .anyMatch(
                call ->
                    "python.service$2e$py.Service.run()".equals(call.callerSignature())
                        && "python.service$2e$py.helper()".equals(call.calleeSignature()));
    if (!callFound) {
      throw new ProcessingException("Python function call was not parsed correctly");
    }
  }
}
