package io.github.ousatov.tools.memgraph.exe.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for managed TypeScript package cache readiness behavior.
 *
 * @author Oleksii Usatov
 */
class ManagedTypescriptPackageTest {

  @TempDir private Path tempDir;

  @Test
  void offlinePackageMarksExistingCompilerReady() throws IOException {
    Path nodeModules =
        tempDir
            .resolve("node_modules")
            .resolve("typescript-" + ManagedTypescriptPackage.DEFAULT_TYPESCRIPT_VERSION);
    Path typescriptDir = nodeModules.resolve("typescript");
    Path compiler = typescriptDir.resolve("lib/typescript.js");
    Files.createDirectories(compiler.getParent());
    Files.writeString(compiler, "");

    ManagedTypescriptPackage typescriptPackage =
        new ManagedTypescriptPackage(
            tempDir, ManagedTypescriptPackage.DEFAULT_TYPESCRIPT_VERSION, RuntimeMode.OFFLINE);

    assertEquals(nodeModules, typescriptPackage.nodeModulesDir());
    assertTrue(Files.isRegularFile(typescriptDir.resolve(".install-complete")));
  }

  @Test
  void offlinePackageAcceptsLeadingVVersion() throws IOException {
    Path nodeModules =
        tempDir
            .resolve("node_modules")
            .resolve("typescript-" + ManagedTypescriptPackage.DEFAULT_TYPESCRIPT_VERSION);
    Path typescriptDir = nodeModules.resolve("typescript");
    Path compiler = typescriptDir.resolve("lib/typescript.js");
    Files.createDirectories(compiler.getParent());
    Files.writeString(compiler, "");

    ManagedTypescriptPackage typescriptPackage =
        new ManagedTypescriptPackage(
            tempDir,
            "v" + ManagedTypescriptPackage.DEFAULT_TYPESCRIPT_VERSION,
            RuntimeMode.OFFLINE);

    assertEquals(nodeModules, typescriptPackage.nodeModulesDir());
    assertTrue(Files.isRegularFile(typescriptDir.resolve(".install-complete")));
  }

  @Test
  void offlinePackageRejectsMissingCompiler() {
    ManagedTypescriptPackage typescriptPackage =
        new ManagedTypescriptPackage(
            tempDir, ManagedTypescriptPackage.DEFAULT_TYPESCRIPT_VERSION, RuntimeMode.OFFLINE);

    ProcessingException error =
        assertThrows(ProcessingException.class, typescriptPackage::nodeModulesDir);

    assertTrue(error.getMessage().contains("is not cached"));
  }
}
