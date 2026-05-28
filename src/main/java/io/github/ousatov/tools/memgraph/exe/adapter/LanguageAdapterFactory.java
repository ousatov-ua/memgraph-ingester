package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.analyze.JsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedCtagsRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedNodeRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedPythonRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedTypescriptPackage;
import io.github.ousatov.tools.memgraph.exe.analyze.ParseService;
import io.github.ousatov.tools.memgraph.exe.analyze.PythonAnalyzer;
import io.github.ousatov.tools.memgraph.exe.analyze.RuntimeMode;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Factory for creating supported {@link LanguageAdapter} instances.
 *
 * @author Oleksii Usatov
 */
public final class LanguageAdapterFactory {

  private LanguageAdapterFactory() {
    // Utility class.
  }

  /** Creates the default list of configured language adapters. */
  public static List<LanguageAdapter<?>> create(
      Path sourceRoot,
      String classpath,
      JsRuntimeOptions jsRuntimeOptions,
      PythonRuntimeOptions pythonRuntimeOptions,
      CtagsRuntimeOptions ctagsRuntimeOptions) {

    ParseService parseService = new ParseService(sourceRoot, classpathEntries(classpath));
    Path jsCacheRoot = resolveCacheRoot(jsRuntimeOptions.cacheRoot());
    ManagedNodeRuntime nodeRuntime =
        new ManagedNodeRuntime(
            jsCacheRoot, jsRuntimeOptions.nodeVersion(), jsRuntimeOptions.runtimeMode());
    ManagedTypescriptPackage typescriptPackage =
        new ManagedTypescriptPackage(
            jsCacheRoot, jsRuntimeOptions.typescriptVersion(), jsRuntimeOptions.runtimeMode());
    Path pythonCacheRoot = resolveCacheRoot(pythonRuntimeOptions.cacheRoot());
    ManagedPythonRuntime pythonRuntime =
        new ManagedPythonRuntime(
            pythonCacheRoot,
            pythonRuntimeOptions.pythonVersion(),
            pythonRuntimeOptions.pythonBuild(),
            pythonRuntimeOptions.runtimeMode());
    Path ctagsCacheRoot = resolveCacheRoot(ctagsRuntimeOptions.cacheRoot());
    ManagedCtagsRuntime ctagsRuntime =
        new ManagedCtagsRuntime(
            ctagsCacheRoot, ctagsRuntimeOptions.ctagsVersion(), ctagsRuntimeOptions.runtimeMode());

    return List.of(
        new JavaLanguageAdapter(parseService),
        new JsLanguageAdapter(new JsAnalyzer(sourceRoot, nodeRuntime, typescriptPackage)),
        new PythonLanguageAdapter(new PythonAnalyzer(sourceRoot, pythonRuntime)),
        new CtagsLanguageAdapter(sourceRoot, new CtagsAnalyzer(sourceRoot, ctagsRuntime)));
  }

  /** JavaScript parser runtime settings. */
  @SuppressWarnings(Const.Warnings.EMPTY_METHOD)
  public record JsRuntimeOptions(
      Path cacheRoot, String nodeVersion, String typescriptVersion, RuntimeMode runtimeMode) {}

  /** Python parser runtime settings. */
  @SuppressWarnings(Const.Warnings.EMPTY_METHOD)
  public record PythonRuntimeOptions(
      Path cacheRoot, String pythonVersion, String pythonBuild, RuntimeMode runtimeMode) {}

  /** Universal Ctags parser runtime settings. */
  @SuppressWarnings(Const.Warnings.EMPTY_METHOD)
  public record CtagsRuntimeOptions(Path cacheRoot, String ctagsVersion, RuntimeMode runtimeMode) {}

  private static List<Path> classpathEntries(String classpath) {
    if (classpath == null || classpath.isBlank()) {
      return List.of();
    }
    return Arrays.stream(classpath.split(File.pathSeparator))
        .map(Path::of)
        .filter(Files::isRegularFile)
        .toList();
  }

  private static Path resolveCacheRoot(Path runtimeCache) {
    return runtimeCache == null ? ManagedNodeRuntime.defaultCacheRoot() : runtimeCache;
  }
}
