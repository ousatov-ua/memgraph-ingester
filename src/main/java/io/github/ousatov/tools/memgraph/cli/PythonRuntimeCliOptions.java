package io.github.ousatov.tools.memgraph.cli;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedPythonRuntime;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.nio.file.Path;
import picocli.CommandLine.Option;

/**
 * Picocli {@code @ArgGroup} binding for Python runtime options.
 *
 * @author Oleksii Usatov
 */
public final class PythonRuntimeCliOptions {

  @Option(
      names = {Const.Cli.PYTHON_RUNTIME_MODE},
      defaultValue = Const.SystemParams.MANAGED,
      description =
          "Python runtime mode: managed downloads standalone CPython and creates a private venv,"
              + " system uses Python 3.9+ from PATH, offline requires a warmed managed cache."
              + " Defaults to managed.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String mode = Const.SystemParams.MANAGED;

  @Option(
      names = {Const.Cli.PYTHON_RUNTIME_CACHE},
      description = "Cache directory for managed CPython downloads and private Python venvs.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public Path cache;

  @Option(
      names = {Const.Cli.PYTHON_VERSION},
      defaultValue = ManagedPythonRuntime.DEFAULT_PYTHON_VERSION,
      description = "Pinned CPython version used for managed Python parsing.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String version = ManagedPythonRuntime.DEFAULT_PYTHON_VERSION;

  @Option(
      names = {Const.Cli.PYTHON_BUILD},
      defaultValue = ManagedPythonRuntime.DEFAULT_PYTHON_BUILD,
      description = "Pinned python-build-standalone release tag used for managed Python parsing.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String build = ManagedPythonRuntime.DEFAULT_PYTHON_BUILD;

  @Option(
      names = {"--check-python-runtime"},
      description =
          "Download/cache the managed Python parser runtime if needed and run a local parser "
              + "smoke check without connecting to Memgraph.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean check;

  public Path resolvedCache() {
    return cache == null ? ManagedPythonRuntime.defaultCacheRoot() : cache;
  }

  public RuntimeMode parsedMode() {
    return RuntimeMode.parse(mode, Const.SystemParams.PYTHON_DISPLAY);
  }
}
