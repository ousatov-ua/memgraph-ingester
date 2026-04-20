package io.github.ousatov.tools.memgraph.extension;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a Memgraph process lifetime for integration tests.
 *
 * <p>Prefers a locally installed native binary; falls back to Docker if the binary is not found.
 * Implements {@link ExtensionContext.Store.CloseableResource} so JUnit 5 shuts it down
 * automatically at the end of the root context (i.e. after all tests complete).
 *
 * @author Oleksii Usatov
 */
public final class MemgraphInstance implements ExtensionContext.Store.CloseableResource {

  private static final Logger log = LoggerFactory.getLogger(MemgraphInstance.class);

  private static final int STARTUP_TIMEOUT_SECONDS = 60;
  private static final List<String> BINARY_CANDIDATES =
      List.of(
          "/opt/homebrew/bin/memgraph",
          "/usr/local/bin/memgraph",
          "/usr/bin/memgraph",
          "/opt/memgraph/bin/memgraph");

  private final String boltUrl;
  private final Process nativeProcess;
  private final Path dataDir;
  private final String dockerContainerName;

  private MemgraphInstance(
      String boltUrl, Process nativeProcess, Path dataDir, String dockerContainerName) {
    this.boltUrl = boltUrl;
    this.nativeProcess = nativeProcess;
    this.dataDir = dataDir;
    this.dockerContainerName = dockerContainerName;
  }

  /**
   * Starts a Memgraph instance, preferring a native binary over Docker.
   *
   * @return the running instance
   * @throws Exception if neither native binary nor Docker is available, or startup fails
   */
  public static MemgraphInstance start() throws Exception {
    int port = findFreePort();
    Path binary = findNativeBinary();
    if (binary != null) {
      log.info("Starting native Memgraph from {} on port {}", binary, port);
      return startNative(binary, port);
    }
    if (isDockerAvailable()) {
      log.info("Starting Memgraph via Docker on port {}", port);
      return startDocker(port);
    }
    throw new IllegalStateException(
        "No Memgraph binary found and Docker is unavailable. "
            + "Install Memgraph (brew install memgraph/memgraph/memgraph on macOS, "
            + "or apt install memgraph on Linux) or install Docker.");
  }

  private static MemgraphInstance startNative(Path binary, int port) throws Exception {
    Path dataDir = Files.createTempDirectory("memgraph-test-");
    Process process =
        new ProcessBuilder(
                binary.toString(),
                "--bolt-port",
                String.valueOf(port),
                "--bolt-address",
                "127.0.0.1",
                "--data-directory",
                dataDir.toString(),
                "--log-file",
                dataDir.resolve("memgraph.log").toString(),
                "--log-level",
                "WARNING")
            .redirectErrorStream(true)
            .start();
    String boltUrl = "bolt://127.0.0.1:" + port;
    waitForBoltReady(boltUrl, STARTUP_TIMEOUT_SECONDS);
    return new MemgraphInstance(boltUrl, process, dataDir, null);
  }

  private static MemgraphInstance startDocker(int port) throws Exception {
    String name = "memgraph-test-" + ProcessHandle.current().pid();
    new ProcessBuilder(
            "docker",
            "run",
            "-d",
            "--rm",
            "-p",
            "127.0.0.1:" + port + ":7687",
            "--name",
            name,
            "memgraph/memgraph:latest",
            "--telemetry-enabled=false")
        .start()
        .waitFor(30, TimeUnit.SECONDS);
    String boltUrl = "bolt://127.0.0.1:" + port;
    waitForBoltReady(boltUrl, STARTUP_TIMEOUT_SECONDS);
    return new MemgraphInstance(boltUrl, null, null, name);
  }

  private static void waitForBoltReady(String boltUrl, int timeoutSeconds) {
    long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
    Exception last = null;
    while (System.currentTimeMillis() < deadline) {
      try (var driver = GraphDatabase.driver(boltUrl, AuthTokens.basic("", ""));
          var session = driver.session()) {
        session.run("RETURN 1").consume();
        log.info("Memgraph is ready at {}", boltUrl);
        return;
      } catch (Exception e) {
        last = e;
        await().atLeast(500, TimeUnit.MILLISECONDS).until(() -> true);
      }
    }
    throw new IllegalStateException(
        "Memgraph at " + boltUrl + " did not become ready within " + timeoutSeconds + "s", last);
  }

  private static Path findNativeBinary() {
    for (String candidate : BINARY_CANDIDATES) {
      Path p = Path.of(candidate);
      if (p.toFile().canExecute()) {
        return p;
      }
    }
    // Probe PATH via 'which'
    try {
      Process which = new ProcessBuilder("which", "memgraph").redirectErrorStream(true).start();
      if (which.waitFor(5, TimeUnit.SECONDS) && which.exitValue() == 0) {
        String found = new String(which.getInputStream().readAllBytes()).strip();
        if (!found.isEmpty()) {
          Path p = Path.of(found);
          if (p.toFile().canExecute()) {
            return p;
          }
        }
      }
    } catch (Exception _) {

      // No action
    }
    return null;
  }

  private static boolean isDockerAvailable() {
    try {
      Process p = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
      return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (Exception _) {
      return false;
    }
  }

  private static int findFreePort() throws IOException {
    try (var ss = new java.net.ServerSocket(0)) {
      ss.setReuseAddress(true);
      return ss.getLocalPort();
    }
  }

  /** Returns the Bolt URL for this instance. */
  public String getBoltUrl() {
    return boltUrl;
  }

  @Override
  public void close() throws Throwable {
    if (nativeProcess != null) {
      nativeProcess.destroy();
      if (!nativeProcess.waitFor(10, TimeUnit.SECONDS)) {
        nativeProcess.destroyForcibly();
      }
    }
    if (dockerContainerName != null) {
      new ProcessBuilder("docker", "stop", dockerContainerName)
          .start()
          .waitFor(30, TimeUnit.SECONDS);
    }
    if (dataDir != null && Files.exists(dataDir)) {
      try (Stream<Path> walk = Files.walk(dataDir)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  var _ = p.toFile().delete();
                });
      } catch (IOException _) {

        // No cation
      }
    }
  }
}
