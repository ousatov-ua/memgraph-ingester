package io.github.ousatov.tools.memgraph.exe.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.def.Const.SystemParams;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for shared managed-runtime host platform detection.
 *
 * @author Oleksii Usatov
 */
class ManagedRuntimePlatformTest {

  @Test
  void macosArm64NormalizesHostIdentity() {
    ManagedRuntimePlatform platform = ManagedRuntimePlatform.from("Mac OS X", "aarch64");

    assertEquals(SystemParams.MACOS, platform.os());
    assertEquals(SystemParams.ARM_64, platform.arch());
    assertTrue(platform.isMacos());
    assertFalse(platform.isWindows());
    assertEquals("tool", platform.executableName("tool", "tool.exe"));
  }

  @Test
  void darwinNormalizesAsMacos() {
    ManagedRuntimePlatform platform = ManagedRuntimePlatform.from("Darwin", "arm64");

    assertEquals(SystemParams.MACOS, platform.os());
    assertEquals(SystemParams.ARM_64, platform.arch());
    assertTrue(platform.isMacos());
    assertFalse(platform.isWindows());
    assertEquals("tool", platform.executableName("tool", "tool.exe"));
  }

  @Test
  void linuxX64NormalizesHostIdentity() {
    ManagedRuntimePlatform platform = ManagedRuntimePlatform.from("Linux", "amd64");

    assertEquals(SystemParams.LINUX, platform.os());
    assertEquals(SystemParams.X_86_64, platform.arch());
    assertTrue(platform.isLinux());
  }

  @Test
  void windowsX86NormalizesHostIdentityAndExecutableName() {
    ManagedRuntimePlatform platform = ManagedRuntimePlatform.from("Windows 11", "i686");

    assertEquals(SystemParams.WINDOWS, platform.os());
    assertEquals(SystemParams.X86, platform.arch());
    assertTrue(platform.isWindows());
    assertEquals("tool.exe", platform.executableName("tool", "tool.exe"));
  }
}
