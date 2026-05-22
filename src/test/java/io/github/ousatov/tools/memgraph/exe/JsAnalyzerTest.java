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

  @TempDir private static Path tempDir;

  @Test
  void analyzesNestedLocalClassDeclarations() throws IOException {
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

    JsAnalysis analysis = analyzer().analyze(specFile);

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

  @Test
  void nestedLocalClassNamesDoNotHideTopLevelClasses() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "collision.ts",
            """
            class Local {
              static make() {}
            }
            describe('scope', () => {
              class Local {
                static make() {}
              }
            });
            Local.make();
            """);

    assertTrue(
        analysis.calls().stream()
            .anyMatch(call -> "js.collision$2e$ts.Local.make()".equals(call.calleeSignature())));
    assertTrue(
        analysis.types().stream()
            .anyMatch(type -> "Local".equals(type.name()) && type.fqn().contains(".$local$")));
  }

  @Test
  void nestedTypesResolveReferencesInTheirLocalScope() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "nested-heritage.ts",
            """
            function build() {
              class Base {}
              class Child extends Base {}
            }
            """);

    String baseFqn =
        analysis.types().stream()
            .filter(type -> "Base".equals(type.name()))
            .findFirst()
            .orElseThrow()
            .fqn();
    String childFqn =
        analysis.types().stream()
            .filter(type -> "Child".equals(type.name()))
            .findFirst()
            .orElseThrow()
            .fqn();
    assertTrue(baseFqn.contains(".$local$"));
    assertTrue(
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    "classExtends".equals(relation.kind())
                        && childFqn.equals(relation.childFqn())
                        && baseFqn.equals(relation.targetFqn())));
  }

  @Test
  void nestedClassMembersDoNotResolveBareCallsOutsideTheClass() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "member-scope.ts",
            """
            describe('scope', () => {
              class Local {
                ping() {}
              }
            });
            ping();
            """);

    assertTrue(
        analysis.calls().stream()
            .noneMatch(call -> call.calleeSignature().endsWith(".Local.ping()")));
  }

  @Test
  void nestedClassStaticInitializersUseContainingFunctionCaller() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "static-init.ts",
            """
            function init() {
              return 1;
            }
            function build() {
              class Local {
                static {
                  init();
                }
                static value = init();
              }
            }
            """);

    long functionScopedCalls =
        analysis.calls().stream()
            .filter(call -> "js.static$2d$init$2e$ts.build()".equals(call.callerSignature()))
            .filter(call -> "js.static$2d$init$2e$ts.init()".equals(call.calleeSignature()))
            .count();

    assertEquals(2, functionScopedCalls);
  }

  @Test
  void nestedFunctionDeclarationsDoNotAttributeStaticInitializersToOuterFunction()
      throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "nested-function-static-init.ts",
            """
            function init() {
              return 1;
            }
            function outer() {
              function inner() {
                class Local {
                  static value = init();
                }
              }
            }
            """);

    assertTrue(
        analysis.calls().stream()
            .noneMatch(
                call ->
                    "js.nested$2d$function$2d$static$2d$init$2e$ts.outer()"
                            .equals(call.callerSignature())
                        && "js.nested$2d$function$2d$static$2d$init$2e$ts.init()"
                            .equals(call.calleeSignature())));
  }

  @Test
  void switchCaseTypeDeclarationsShareSwitchScope() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "switch-scope.ts",
            """
            switch (kind) {
              case 1:
                class Base {}
                break;
              case 2:
                class Child extends Base {}
                break;
            }
            """);

    String baseFqn =
        analysis.types().stream()
            .filter(type -> "Base".equals(type.name()))
            .findFirst()
            .orElseThrow()
            .fqn();
    String childFqn =
        analysis.types().stream()
            .filter(type -> "Child".equals(type.name()))
            .findFirst()
            .orElseThrow()
            .fqn();

    assertTrue(
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    "classExtends".equals(relation.kind())
                        && childFqn.equals(relation.childFqn())
                        && baseFqn.equals(relation.targetFqn())));
  }

  @Test
  void exportedDefaultFunctionExpressionsEmitNestedClassDeclarations() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "export-default-nested.ts",
            """
            function init() {
              return 1;
            }
            export default (() => {
              class Local {
                static value = init();
              }
            });
            """);

    JsAnalysis.TypeDecl localClass =
        analysis.types().stream()
            .filter(type -> "Local".equals(type.name()))
            .findFirst()
            .orElseThrow();

    assertTrue(localClass.fqn().contains(".$local$"));
    assertTrue(
        analysis.calls().stream()
            .anyMatch(
                call ->
                    "js.export$2d$default$2d$nested$2e$ts.default()".equals(call.callerSignature())
                        && "js.export$2d$default$2d$nested$2e$ts.init()"
                            .equals(call.calleeSignature())));
  }

  @Test
  void loopHeaderClassExpressionsDoNotLeakIntoOuterTypeScope() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "loop-scope.ts",
            """
            function build() {
              let C = class {};
              for (let C = class {}; false;) {
              }
              class D extends C {}
            }
            """);

    String outerClassFqn =
        analysis.types().stream()
            .filter(type -> "C".equals(type.name()))
            .filter(type -> type.startLine() == 2)
            .findFirst()
            .orElseThrow()
            .fqn();
    String childFqn =
        analysis.types().stream()
            .filter(type -> "D".equals(type.name()))
            .findFirst()
            .orElseThrow()
            .fqn();

    assertTrue(
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    "classExtends".equals(relation.kind())
                        && childFqn.equals(relation.childFqn())
                        && outerClassFqn.equals(relation.targetFqn())));
  }

  @Test
  void analyzesCallsInsideAnonymousClassExpressions() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "anonymous.ts",
            """
            function init() {
              return 1;
            }
            consume(class {
              static value = init();
            });
            """);

    assertTrue(
        analysis.calls().stream()
            .anyMatch(call -> "js.anonymous$2e$ts.init()".equals(call.calleeSignature())));
  }

  @Test
  void reExportedClassAliasesResolveAfterHelperSplit() throws IOException {
    Files.writeString(
        tempDir.resolve("source.ts"),
        """
        export class Source {
          constructor(id: string) {}
        }
        """);
    JsAnalysis analysis =
        analyzeSource(
            "barrel.ts",
            """
            export { Source } from './source';
            """);

    assertTrue(
        analysis.types().stream()
            .anyMatch(
                type ->
                    "class".equals(type.kind())
                        && "Source".equals(type.name())
                        && "js.barrel$2e$ts.Source".equals(type.fqn())));
    assertTrue(
        analysis.calls().stream()
            .anyMatch(
                call ->
                    "js.barrel$2e$ts.Source.<init>(string)".equals(call.callerSignature())
                        && "js.source$2e$ts.Source".equals(call.calleeOwnerFqn())
                        && "<init>".equals(call.calleeName())));
  }

  @Test
  void localFunctionExpressionsEmitNestedTypesWithoutOuterStaticInitializerCalls()
      throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "local-function-expression.ts",
            """
            function init() {
              return 1;
            }
            function outer() {
              const local = () => {
                class Local {
                  static value = init();
                }
              };
            }
            """);

    assertTrue(
        analysis.types().stream()
            .anyMatch(
                type ->
                    "class".equals(type.kind())
                        && "Local".equals(type.name())
                        && type.fqn().contains(".$local$")));
    assertTrue(
        analysis.calls().stream()
            .noneMatch(
                call ->
                    "js.local$2d$function$2d$expression$2e$ts.outer()"
                            .equals(call.callerSignature())
                        && "js.local$2d$function$2d$expression$2e$ts.init()"
                            .equals(call.calleeSignature())));
  }

  private JsAnalysis analyzeSource(String fileName, String source) throws IOException {
    Path sourceFile = tempDir.resolve(fileName);
    Files.writeString(sourceFile, source);
    return analyzer().analyze(sourceFile);
  }

  private JsAnalyzer analyzer() {
    assumeTrue(systemNodeAvailable(), "JavaScript analyzer helper test requires system node");
    Path runtimeCache = tempDir.resolve("runtime");
    ManagedTypescriptPackage typescriptPackage = managedTypescriptPackageOrSkip(runtimeCache);
    return new JsAnalyzer(
        tempDir,
        new ManagedNodeRuntime(
            runtimeCache, ManagedNodeRuntime.DEFAULT_NODE_VERSION, RuntimeMode.SYSTEM),
        typescriptPackage);
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
