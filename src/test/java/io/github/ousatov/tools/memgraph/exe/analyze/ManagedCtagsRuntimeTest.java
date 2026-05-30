package io.github.ousatov.tools.memgraph.exe.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedCtagsRuntimeTest {

  @TempDir private Path tempDir;

  @Test
  void defaultCacheRootUsesSharedManagedRuntimeCache() {
    assertEquals(ManagedNodeRuntime.defaultCacheRoot(), ManagedCtagsRuntime.defaultCacheRoot());
  }

  @Test
  void managedLatestUsesCachedExecutableBeforeFetchingReleaseMetadata() throws IOException {
    ManagedRuntimePlatform platform = ManagedRuntimePlatform.current();
    Path executable = cachedExecutable(tempDir, "z-cached", platform);
    Files.createDirectories(executable.getParent());
    Files.writeString(executable, "#!/bin/sh\nexit 0\n");
    assumeTrue(executable.toFile().setExecutable(true, true) || Files.isExecutable(executable));
    Files.writeString(installDir(tempDir, "z-cached", platform).resolve(".install-complete"), "\n");

    ManagedCtagsRuntime runtime =
        new ManagedCtagsRuntime(
            tempDir, ManagedCtagsRuntime.DEFAULT_CTAGS_VERSION, RuntimeMode.MANAGED);

    assertEquals(executable, runtime.ctagsExecutable());
  }

  @Test
  void offlineLatestUsesCachedExecutable() throws IOException {
    ManagedRuntimePlatform platform = ManagedRuntimePlatform.current();
    Path executable = cachedExecutable(tempDir, "z-cached", platform);
    Files.createDirectories(executable.getParent());
    Files.writeString(executable, "#!/bin/sh\nexit 0\n");
    assumeTrue(executable.toFile().setExecutable(true, true) || Files.isExecutable(executable));
    Files.writeString(installDir(tempDir, "z-cached", platform).resolve(".install-complete"), "\n");

    ManagedCtagsRuntime runtime =
        new ManagedCtagsRuntime(
            tempDir, ManagedCtagsRuntime.DEFAULT_CTAGS_VERSION, RuntimeMode.OFFLINE);

    assertEquals(executable, runtime.ctagsExecutable());
  }

  @Test
  void parsesGithubReleaseAssetsPage() {
    io.github.ousatov.tools.memgraph.vo.analysis.ctags.Release release =
        ManagedCtagsRuntime.parseReleaseAssetsPage(
            "2026.05.26+abc",
            """
            <a href="/universal-ctags/ctags-nightly-build/releases/download/2026.05.26%2Babc/uctags-2026.05.26-macos-15.0-arm64.release.tar.xz" rel="nofollow">
              <span data-view-component="true" class="Truncate-text text-bold">uctags-2026.05.26-macos-15.0-arm64.release.tar.xz</span>
              <span data-view-component="true" class="Truncate-text">sha256:fc0ac8f0f5493c37d1c7e0a3d922a6c148354bd07823849c5273af1b70197932</span>
            </a>
            """);

    io.github.ousatov.tools.memgraph.vo.analysis.ctags.ReleaseAsset asset =
        release.assets().getFirst();
    assertEquals("2026.05.26+abc", release.tag());
    assertEquals("uctags-2026.05.26-macos-15.0-arm64.release.tar.xz", asset.name());
    assertEquals(
        "sha256:fc0ac8f0f5493c37d1c7e0a3d922a6c148354bd07823849c5273af1b70197932",
        asset.digest().orElseThrow());
    assertEquals(
        "https://github.com/universal-ctags/ctags-nightly-build/releases/download/2026.05.26%2Babc/uctags-2026.05.26-macos-15.0-arm64.release.tar.xz",
        asset.url());
  }

  @Test
  void parsesGithubReleaseAssetsPageWithSiblingDigest() {
    io.github.ousatov.tools.memgraph.vo.analysis.ctags.Release release =
        ManagedCtagsRuntime.parseReleaseAssetsPage(
            "2026.05.26+abc",
            """
            <li data-view-component="true" class="Box-row d-flex flex-column flex-md-row">
              <div class="d-flex flex-justify-start flex-items-center">
                <a href="/universal-ctags/ctags-nightly-build/releases/download/2026.05.26%2Babc/uctags-2026.05.26-macos-10.15-arm64.release.tar.xz" rel="nofollow">
                  <span data-view-component="true" class="Truncate-text text-bold">uctags-2026.05.26-macos-10.15-arm64.release.tar.xz</span>
                  <span data-view-component="true" class="Truncate-text"></span>
                </a>
              </div>
              <div class="d-flex flex-auto flex-justify-end flex-items-center">
                <span data-view-component="true" class="Truncate-text">sha256:8e20de248093ff6cb8aee05ea58149765feda4b974550282bf6b8c2bcd4b59ad</span>
              </div>
            </li>
            """);

    io.github.ousatov.tools.memgraph.vo.analysis.ctags.ReleaseAsset asset =
        release.assets().getFirst();
    assertEquals("2026.05.26+abc", release.tag());
    assertEquals("uctags-2026.05.26-macos-10.15-arm64.release.tar.xz", asset.name());
    assertEquals(
        "sha256:8e20de248093ff6cb8aee05ea58149765feda4b974550282bf6b8c2bcd4b59ad",
        asset.digest().orElseThrow());
  }

  @Test
  void parsesGithubReleaseAssetsPageWithoutDigest() {
    io.github.ousatov.tools.memgraph.vo.analysis.ctags.Release release =
        ManagedCtagsRuntime.parseReleaseAssetsPage(
            "v6.1.0",
            """
            <a href="/universal-ctags/ctags-win32/releases/download/v6.1.0/ctags-v6.1.0-x64.zip" rel="nofollow">
              <span data-view-component="true" class="Truncate-text text-bold">ctags-v6.1.0-x64.zip</span>
              <span data-view-component="true" class="Truncate-text">9.53 MB</span>
            </a>
            """);

    io.github.ousatov.tools.memgraph.vo.analysis.ctags.ReleaseAsset asset =
        release.assets().getFirst();
    assertEquals("v6.1.0", release.tag());
    assertEquals("ctags-v6.1.0-x64.zip", asset.name());
    assertEquals(Optional.empty(), asset.digest());
    assertEquals(
        "https://github.com/universal-ctags/ctags-win32/releases/download/v6.1.0/ctags-v6.1.0-x64.zip",
        asset.url());
  }

  private static Path cachedExecutable(
      Path cacheRoot, String version, ManagedRuntimePlatform platform) {
    return installDir(cacheRoot, version, platform)
        .resolve("bin")
        .resolve(platform.executableName("ctags", "ctags.exe"));
  }

  private static Path installDir(Path cacheRoot, String version, ManagedRuntimePlatform platform) {
    return cacheRoot
        .resolve("ctags")
        .resolve(version)
        .resolve(platform.os() + "-" + platform.arch());
  }
}
