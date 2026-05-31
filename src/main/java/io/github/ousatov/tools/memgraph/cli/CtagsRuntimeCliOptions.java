package io.github.ousatov.tools.memgraph.cli;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedCtagsRuntime;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.nio.file.Path;
import picocli.CommandLine.Option;

/**
 * Picocli {@code @ArgGroup} binding for Universal Ctags runtime options.
 *
 * @author Oleksii Usatov
 */
public final class CtagsRuntimeCliOptions {

  @Option(
      names = {"--ctags-runtime-mode"},
      defaultValue = Const.SystemParams.MANAGED,
      description =
          "Universal Ctags runtime mode: managed downloads a verified ctags binary, system uses "
              + "ctags from PATH, offline requires a warmed managed cache. Defaults to managed.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String mode = Const.SystemParams.MANAGED;

  @Option(
      names = {"--ctags-runtime-cache"},
      description = "Cache directory for managed Universal Ctags downloads.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public Path cache;

  @Option(
      names = {"--ctags-version"},
      defaultValue = ManagedCtagsRuntime.DEFAULT_CTAGS_VERSION,
      description = "Universal Ctags release tag used for managed fallback parsing, or latest.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String version = ManagedCtagsRuntime.DEFAULT_CTAGS_VERSION;

  @Option(
      names = {"--check-ctags-runtime"},
      description =
          "Download/cache the managed Universal Ctags runtime if needed and run a local parser "
              + "smoke check without connecting to Memgraph.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean check;

  public Path resolvedCache() {
    return cache == null ? ManagedCtagsRuntime.defaultCacheRoot() : cache;
  }

  public RuntimeMode parsedMode() {
    return RuntimeMode.parse(mode, Const.SystemParams.CTAGS);
  }
}
