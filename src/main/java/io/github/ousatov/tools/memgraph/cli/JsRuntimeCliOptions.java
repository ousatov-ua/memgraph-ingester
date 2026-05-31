package io.github.ousatov.tools.memgraph.cli;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedNodeRuntime;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedTypescriptPackage;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.nio.file.Path;
import picocli.CommandLine.Option;

/**
 * Picocli {@code @ArgGroup} binding for JavaScript runtime options.
 *
 * @author Oleksii Usatov
 */
public final class JsRuntimeCliOptions {

  @Option(
      names = {Const.Cli.JS_RUNTIME_MODE},
      defaultValue = Const.SystemParams.MANAGED,
      description =
          "JavaScript runtime mode: managed downloads Node.js, system uses node from PATH,"
              + " offline requires a warmed managed cache. Defaults to managed.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String mode = Const.SystemParams.MANAGED;

  @Option(
      names = {Const.Cli.JS_RUNTIME_CACHE},
      description = "Cache directory for managed Node.js and TypeScript downloads.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public Path cache;

  @Option(
      names = {Const.Cli.JS_NODE_VERSION},
      defaultValue = ManagedNodeRuntime.DEFAULT_NODE_VERSION,
      description = "Pinned Node.js version used for managed JavaScript parsing.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String nodeVersion = ManagedNodeRuntime.DEFAULT_NODE_VERSION;

  @Option(
      names = {Const.Cli.JS_TYPESCRIPT_VERSION},
      defaultValue = ManagedTypescriptPackage.DEFAULT_TYPESCRIPT_VERSION,
      description = "Pinned TypeScript compiler package used by the JavaScript analyzer.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public String typescriptVersion = ManagedTypescriptPackage.DEFAULT_TYPESCRIPT_VERSION;

  @Option(
      names = {"--check-js-runtime"},
      description =
          "Download/cache the managed JavaScript parser runtime if needed and run a local "
              + "parser smoke check without connecting to Memgraph.")
  @SuppressWarnings(Const.Warnings.UNUSED)
  public boolean check;

  public Path resolvedCache() {
    return cache == null ? ManagedNodeRuntime.defaultCacheRoot() : cache;
  }

  public RuntimeMode parsedMode() {
    return RuntimeMode.parse(mode, "JS");
  }
}
