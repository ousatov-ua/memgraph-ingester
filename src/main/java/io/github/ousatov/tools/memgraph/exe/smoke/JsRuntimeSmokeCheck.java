package io.github.ousatov.tools.memgraph.exe.smoke;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.analyze.JsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedNodeRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedTypescriptPackage;
import io.github.ousatov.tools.memgraph.vo.analysis.JsAnalysis;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smoke check for the managed JavaScript / TypeScript parser runtime.
 *
 * @author Oleksii Usatov
 */
public final class JsRuntimeSmokeCheck extends RuntimeSmokeCheck {

  private static final Logger log = LoggerFactory.getLogger(JsRuntimeSmokeCheck.class);

  private static final String EXPORT_CONST_VALUE_1 = "export const value = 1;\n";

  private final Path cacheRoot;
  private final String nodeVersion;
  private final String typescriptVersion;
  private final RuntimeMode runtimeMode;

  public JsRuntimeSmokeCheck(
      Path cacheRoot, String nodeVersion, String typescriptVersion, RuntimeMode runtimeMode) {
    super(log);
    this.cacheRoot = cacheRoot;
    this.nodeVersion = nodeVersion;
    this.typescriptVersion = typescriptVersion;
    this.runtimeMode = runtimeMode;
  }

  @Override
  protected String displayName() {
    return "JavaScript parser";
  }

  @Override
  protected String tempDirPrefix() {
    return "memgraph-ingester-js-runtime-check-";
  }

  @Override
  protected Path cacheRoot() {
    return cacheRoot;
  }

  @Override
  protected void execute(Path tempDir) throws IOException {
    Path defaultClass = tempDir.resolve("default-class.js");
    Path defaultFunction = tempDir.resolve("default-function.js");
    Path indexJs = tempDir.resolve("index.js");
    Path indexTs = tempDir.resolve("index.ts");
    Path hyphenModule = tempDir.resolve("my-file.js");
    Path underscoreModule = tempDir.resolve("my_file.js");
    Path dottedModule = tempDir.resolve("a.b.js");
    Path underscoredDottedModule = tempDir.resolve("a_b.js");
    Path constructorCall = tempDir.resolve("constructor-call.js");
    Path uninitializedVariable = tempDir.resolve("uninitialized-variable.ts");
    Path tsconfigBase = tempDir.resolve("tsconfig.base.json");
    Path tsconfig = tempDir.resolve("tsconfig.json");
    Path aliasConsumer = tempDir.resolve("alias-consumer.ts");
    Path specificAliasTarget = tempDir.resolve("src/app/specific.ts");
    Path broadAliasTarget = tempDir.resolve("src/shared/specific.ts");
    Files.writeString(
        defaultClass,
        """
        export default class {
          constructor(value) {
            this.value = value;
          }
        }
        """);
    Files.writeString(
        defaultFunction,
        """
        export default function(value) {
          return value;
        }
        """);
    Files.writeString(indexJs, "export const jsValue = 1;\n");
    Files.writeString(indexTs, "export const tsValue: number = 1;\n");
    Files.writeString(hyphenModule, EXPORT_CONST_VALUE_1);
    Files.writeString(underscoreModule, EXPORT_CONST_VALUE_1);
    Files.writeString(dottedModule, EXPORT_CONST_VALUE_1);
    Files.writeString(underscoredDottedModule, EXPORT_CONST_VALUE_1);
    Files.writeString(
        constructorCall,
        """
        class Service {
          constructor(value) {
            this.value = value;
          }
        }

        export function make() {
          return new Service(1);
        }
        """);
    Files.writeString(
        uninitializedVariable,
        """
        let deferredValue: string;

        export function value() {
          return deferredValue;
        }
        """);
    Files.writeString(
        tsconfigBase,
        """
        {
          "compilerOptions": {
            "baseUrl": ".",
            "paths": {
              "@/*": ["src/shared/*"],
              "@app/*": ["src/app/*"]
            }
          }
        }
        """);
    Files.writeString(
        tsconfig,
        """
        {
          "extends": "./tsconfig.base.json"
        }
        """);
    Files.createDirectories(specificAliasTarget.getParent());
    Files.createDirectories(broadAliasTarget.getParent());
    Files.writeString(specificAliasTarget, "export class Base {}\n");
    Files.writeString(broadAliasTarget, "export class Base {}\n");
    Files.writeString(
        aliasConsumer,
        """
        import { Base } from '@app/specific';

        export class Derived extends Base {}
        """);

    JsAnalyzer analyzer =
        new JsAnalyzer(
            tempDir,
            new ManagedNodeRuntime(cacheRoot, nodeVersion, runtimeMode),
            new ManagedTypescriptPackage(cacheRoot, typescriptVersion, runtimeMode));
    assertDefaultClass(analyzer.analyze(defaultClass));
    assertDefaultFunction(analyzer.analyze(defaultFunction));
    assertDistinctModuleFqns(analyzer.analyze(indexJs), analyzer.analyze(indexTs));
    assertDistinctModuleFqns(
        analyzer.analyze(hyphenModule),
        analyzer.analyze(underscoreModule),
        "hyphen and underscore module FQNs");
    assertDistinctModuleFqns(
        analyzer.analyze(dottedModule),
        analyzer.analyze(underscoredDottedModule),
        "dotted and underscored module FQNs");
    assertConstructorCall(analyzer.analyze(constructorCall));
    assertTsconfigPathAlias(analyzer.analyze(aliasConsumer));
    analyzer.analyze(uninitializedVariable);
  }

  static void assertDefaultClass(JsAnalysis analysis) {
    boolean defaultClassFound =
        analysis.types().stream()
            .anyMatch(
                type ->
                    Params.CLASS.equals(type.kind())
                        && Params.DEFAULT.equals(type.name())
                        && type.hasConstructor());
    boolean constructorFound =
        analysis.members().stream()
            .anyMatch(
                member ->
                    Params.METHOD.equals(member.memberType())
                        && Params.CONSTRUCTOR.equals(member.kind())
                        && Labels.INIT.equals(member.name()));
    if (!defaultClassFound || !constructorFound) {
      throw new ProcessingException("Default anonymous class was not parsed correctly");
    }
  }

  static void assertDefaultFunction(JsAnalysis analysis) {
    boolean defaultFunctionFound =
        analysis.members().stream()
            .anyMatch(
                member ->
                    Params.METHOD.equals(member.memberType())
                        && Params.FUNCTION.equals(member.kind())
                        && Params.DEFAULT.equals(member.name()));
    if (!defaultFunctionFound) {
      throw new ProcessingException("Default anonymous function was not parsed correctly");
    }
  }

  static void assertDistinctModuleFqns(JsAnalysis jsAnalysis, JsAnalysis tsAnalysis) {
    assertDistinctModuleFqns(jsAnalysis, tsAnalysis, "JavaScript and TypeScript module FQNs");
  }

  static void assertDistinctModuleFqns(
      JsAnalysis jsAnalysis, JsAnalysis tsAnalysis, String description) {
    if (jsAnalysis.moduleFqn().equals(tsAnalysis.moduleFqn())) {
      throw new ProcessingException(description + " must be distinct");
    }
  }

  static void assertConstructorCall(JsAnalysis analysis) {
    boolean constructorCallFound =
        analysis.calls().stream()
            .anyMatch(
                call ->
                    call.callerSignature().endsWith(".make()")
                        && call.calleeSignature().endsWith(".Service.<init>(any)"));
    if (!constructorCallFound) {
      throw new ProcessingException("JavaScript constructor call was not parsed correctly");
    }
  }

  static void assertTsconfigPathAlias(JsAnalysis analysis) {
    boolean specificAliasFound =
        analysis.relations().stream()
            .anyMatch(
                relation ->
                    Params.CLASS_EXTENDS.equals(relation.kind())
                        && relation.childFqn().endsWith(".alias$2d$consumer$2e$ts.Derived")
                        && "js.src.app.specific$2e$ts.Base".equals(relation.targetFqn()));
    if (!specificAliasFound) {
      throw new ProcessingException(
          "TypeScript extended tsconfig path alias was not resolved to the most specific target");
    }
  }
}
