package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.exe.analyze.JsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedNodeRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedTypescriptPackage;
import io.github.ousatov.tools.memgraph.exe.analyze.ParseService;
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

  private LanguageAdapterFactory() {}

  /** Creates the default list of configured language adapters. */
  public static List<LanguageAdapter<?>> create(
      Path sourceRoot,
      String classpath,
      Path jsRuntimeCache,
      String jsNodeVersion,
      String jsTypescriptVersion,
      RuntimeMode selectedRuntimeMode) {

    ParseService parseService = new ParseService(sourceRoot, classpathEntries(classpath));
    Path cacheRoot = resolveCacheRoot(jsRuntimeCache);
    ManagedNodeRuntime nodeRuntime =
        new ManagedNodeRuntime(cacheRoot, jsNodeVersion, selectedRuntimeMode);
    ManagedTypescriptPackage typescriptPackage =
        new ManagedTypescriptPackage(cacheRoot, jsTypescriptVersion, selectedRuntimeMode);

    return List.of(
        new JavaLanguageAdapter(parseService),
        new JsLanguageAdapter(new JsAnalyzer(sourceRoot, nodeRuntime, typescriptPackage)));
  }

  private static List<Path> classpathEntries(String classpath) {
    if (classpath == null || classpath.isBlank()) {
      return List.of();
    }
    return Arrays.stream(classpath.split(File.pathSeparator))
        .map(Path::of)
        .filter(Files::isRegularFile)
        .toList();
  }

  private static Path resolveCacheRoot(Path jsRuntimeCache) {
    return jsRuntimeCache == null ? ManagedNodeRuntime.defaultCacheRoot() : jsRuntimeCache;
  }
}
