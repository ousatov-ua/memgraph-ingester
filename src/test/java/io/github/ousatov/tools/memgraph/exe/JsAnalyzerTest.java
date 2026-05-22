package io.github.ousatov.tools.memgraph.exe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the JavaScript and TypeScript analyzer helper.
 *
 * @author Oleksii Usatov
 */
class JsAnalyzerTest {

  private static final String LOCAL_CLASS_NAME = "TestImplementationBaseRequest";
  private static final String BASE_CLASS_FQN = "js.base$2e$ts.BaseTranslationRequestService";

  @TempDir private Path tempDir;

  @Test
  void analyzesNestedLocalClassDeclarations() throws IOException {
    assumeTrue(systemNodeAvailable(), "JavaScript analyzer helper test requires system node");
    Path runtimeCache = tempDir.resolve("runtime");
    ManagedTypescriptPackage typescriptPackage = managedTypescriptPackageOrSkip(runtimeCache);
    JsAnalyzer analyzer =
        new JsAnalyzer(
            tempDir,
            new ManagedNodeRuntime(
                runtimeCache, ManagedNodeRuntime.DEFAULT_NODE_VERSION, RuntimeMode.SYSTEM),
            typescriptPackage);
    Path baseFile = tempDir.resolve("base.ts");
    Path specFile = tempDir.resolve("base.spec.ts");
    Files.writeString(baseFile, "export class BaseTranslationRequestService {}\n");
    Files.writeString(
        specFile,
        """
        import { BaseTranslationRequestService } from './base';
        describe('url generation', () => {
          class TestImplementationBaseRequest extends BaseTranslationRequestService {}
        });
        """);

    JsAnalysis analysis = analyzer.analyze(specFile);

    JsAnalysis.TypeDecl localClass =
        analysis.types().stream()
            .filter(type -> LOCAL_CLASS_NAME.equals(type.name()))
            .findFirst()
            .orElseThrow();
    assertEquals("class", localClass.kind());
    assertEquals(LOCAL_CLASS_NAME, localClass.name());
    assertTrue(localClass.fqn().contains(".$local$"));
    assertTrue(
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    "classExtends".equals(relation.kind())
                        && localClass.fqn().equals(relation.childFqn())
                        && BASE_CLASS_FQN.equals(relation.targetFqn())));
  }

  private static ManagedTypescriptPackage managedTypescriptPackageOrSkip(Path runtimeCache) {
    ManagedTypescriptPackage typescriptPackage =
        new ManagedTypescriptPackage(
            runtimeCache, ManagedTypescriptPackage.DEFAULT_TYPESCRIPT_VERSION, RuntimeMode.MANAGED);
    try {
      typescriptPackage.nodeModulesDir();
      return typescriptPackage;
    } catch (ProcessingException e) {
      assumeTrue(false, "Managed TypeScript package unavailable: " + e.getMessage());
      throw e;
    }
  }

  private static boolean systemNodeAvailable() {
    try {
      Process process =
          new ProcessBuilder("node", "--version")
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
