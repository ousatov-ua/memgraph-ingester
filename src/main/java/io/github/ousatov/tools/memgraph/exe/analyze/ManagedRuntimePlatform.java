package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const.SystemParams;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.util.Locale;

/**
 * Host operating-system and architecture identity shared by managed runtime resolvers.
 *
 * @author Oleksii Usatov
 */
record ManagedRuntimePlatform(String os, String arch) {

  static ManagedRuntimePlatform current() {
    return from(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
  }

  static ManagedRuntimePlatform from(String osName, String archName) {
    return new ManagedRuntimePlatform(normalizeOs(osName), normalizeArch(archName));
  }

  static boolean isCurrentWindows() {
    return isWindowsName(System.getProperty("os.name", ""));
  }

  private static String normalizeOs(String rawName) {
    String osName = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT);
    if (isWindowsName(rawName)) {
      return SystemParams.WINDOWS;
    }
    if (osName.contains("mac") || osName.contains("darwin")) {
      return SystemParams.MACOS;
    }
    if (osName.contains(SystemParams.LINUX)) {
      return SystemParams.LINUX;
    }
    throw new ProcessingException("Unsupported operating system for managed runtime: " + rawName);
  }

  private static boolean isWindowsName(String rawName) {
    return rawName != null && rawName.toLowerCase(Locale.ROOT).startsWith("win");
  }

  private static String normalizeArch(String rawName) {
    String archName = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT);
    return switch (archName) {
      case SystemParams.AARCH_64, SystemParams.ARM_64 -> SystemParams.ARM_64;
      case SystemParams.AMD_64, SystemParams.X_86_64 -> SystemParams.X_86_64;
      case SystemParams.X86, SystemParams.I_386, SystemParams.I_686 -> SystemParams.X86;
      default -> throw new ProcessingException("Unsupported CPU architecture: " + rawName);
    };
  }

  boolean isWindows() {
    return os.equals(SystemParams.WINDOWS);
  }

  boolean isMacos() {
    return os.equals(SystemParams.MACOS);
  }

  boolean isLinux() {
    return os.equals(SystemParams.LINUX);
  }

  String executableName(String unixName, String windowsName) {
    return isWindows() ? windowsName : unixName;
  }
}
