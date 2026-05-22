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
  void analyzesNamedClassExpressionsInCallArguments() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "pipe.mock.ts",
            """
            import { Pipe } from '@angular/core';

            export function pipeMock(options: Pipe): Pipe {
              const metadata: Pipe = {
                name: options.name,
              };

              return Pipe(metadata)(class PipeMock {});
            }
            """);

    JsAnalysis.TypeDecl pipeMock =
        analysis.types().stream()
            .filter(type -> "PipeMock".equals(type.name()))
            .findFirst()
            .orElseThrow();

    assertEquals("class", pipeMock.kind());
    assertTrue(pipeMock.fqn().contains(".$local$"));
  }

  @Test
  void namedClassExpressionsDoNotHideTopLevelClasses() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "class-expression-scope.ts",
            """
            class Helper {}
            decorate(class Helper {});
            class Consumer extends Helper {}
            """);

    String topLevelHelperFqn = "js.class$2d$expression$2d$scope$2e$ts.Helper";
    String consumerFqn = "js.class$2d$expression$2d$scope$2e$ts.Consumer";

    assertTrue(
        analysis.types().stream()
            .anyMatch(type -> "Helper".equals(type.name()) && type.fqn().contains(".$local$")));
    assertTrue(
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    "classExtends".equals(relation.kind())
                        && consumerFqn.equals(relation.childFqn())
                        && topLevelHelperFqn.equals(relation.targetFqn())));
  }

  @Test
  void parenthesizedVariableClassExpressionsUseVariableName() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "parenthesized-alias.ts",
            """
            const Alias = (class Named {
              static make() {}
            });
            Alias.make();
            """);

    assertTrue(
        analysis.types().stream()
            .anyMatch(
                type ->
                    "Alias".equals(type.name())
                        && "js.parenthesized$2d$alias$2e$ts.Alias".equals(type.fqn())));
    assertTrue(analysis.types().stream().noneMatch(type -> "Named".equals(type.name())));
    assertTrue(
        analysis.calls().stream()
            .anyMatch(
                call ->
                    "js.parenthesized$2d$alias$2e$ts.Alias.make()".equals(call.calleeSignature())));
  }

  @Test
  void assertedVariableClassExpressionsUseVariableName() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "asserted-alias.ts",
            """
            const Alias = (class Named {
              static make() {}
            }) as any;
            Alias.make();
            """);

    assertTrue(
        analysis.types().stream()
            .anyMatch(
                type ->
                    "Alias".equals(type.name())
                        && "js.asserted$2d$alias$2e$ts.Alias".equals(type.fqn())));
    assertTrue(analysis.types().stream().noneMatch(type -> "Named".equals(type.name())));
    assertTrue(
        analysis.calls().stream()
            .anyMatch(
                call -> "js.asserted$2d$alias$2e$ts.Alias.make()".equals(call.calleeSignature())));
  }

  @Test
  void namedVariableClassExpressionsPreserveClassLocalSelfBinding() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "named-class-self.ts",
            """
            const Alias = class Named {
              static build() {
                return new Named();
              }
            };
            """);

    assertTrue(
        analysis.calls().stream()
            .anyMatch(
                call ->
                    "js.named$2d$class$2d$self$2e$ts.Alias.build()".equals(call.callerSignature())
                        && "js.named$2d$class$2d$self$2e$ts.Alias.<init>()"
                            .equals(call.calleeSignature())));
  }

  @Test
  void resolvesCallsThroughTypedThisPropertyReceivers() throws IOException {
    Files.writeString(
        tempDir.resolve("repository.service.ts"),
        """
        export class RepositoryService {
          getRepos() {}
        }
        """);
    JsAnalysis analysis =
        analyzeSource(
            "repository.component.ts",
            """
            import { RepositoryService } from './repository.service';

            class RepositoryComponent {
              constructor(private repositoryService: RepositoryService) {}

              load() {
                return this.repositoryService.getRepos();
              }
            }
            """);

    assertTrue(
        analysis.calls().stream()
            .anyMatch(
                call ->
                    "js.repository$2e$component$2e$ts.RepositoryComponent.load()"
                            .equals(call.callerSignature())
                        && "js.repository$2e$service$2e$ts.RepositoryService"
                            .equals(call.calleeOwnerFqn())
                        && "getRepos".equals(call.calleeName())));
  }

  @Test
  void topLevelRouteConstUsesSyntheticModuleOwner() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "app.module.ts",
            """
            import { NgModule } from '@angular/core';
            import { RouterModule, Routes } from '@angular/router';

            const appRoutes: Routes = [];

            @NgModule({
              imports: [RouterModule.forRoot(appRoutes)],
            })
            export class AppModule {}
            """);

    assertEquals("js.app$2e$module$2e$ts", analysis.moduleFqn());
    assertTrue(
        analysis.members().stream()
            .anyMatch(
                member ->
                    "js.app$2e$module$2e$ts".equals(member.ownerFqn())
                        && "field".equals(member.memberType())
                        && "appRoutes".equals(member.name())));
    assertTrue(
        analysis.types().stream()
            .anyMatch(
                type ->
                    "class".equals(type.kind())
                        && "js.app$2e$module$2e$ts.AppModule".equals(type.fqn())));
  }

  @Test
  void ignoresExternalTypedThisPropertyReceivers() throws IOException {
    JsAnalysis analysis =
        analyzeSource(
            "external-receiver.ts",
            """
            import { Router } from '@angular/router';

            class NavigationComponent {
              constructor(private router: Router) {}

              go() {
                return this.router.navigate(['/home']);
              }
            }
            """);

    assertTrue(
        analysis.calls().stream()
            .noneMatch(
                call ->
                    "@angular/router.Router".equals(call.calleeOwnerFqn())
                        && "navigate".equals(call.calleeName())));
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
