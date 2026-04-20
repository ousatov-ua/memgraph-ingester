package io.github.ousatov.tools.memgraph.extension;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Manages a Memgraph Docker container for integration tests via Testcontainers.
 *
 * <p>Implements {@link ExtensionContext.Store.CloseableResource} so JUnit 5 stops the container
 * automatically after all tests complete.
 *
 * @author Oleksii Usatov
 */
public final class MemgraphInstance implements ExtensionContext.Store.CloseableResource {

  private static final Logger log = LoggerFactory.getLogger(MemgraphInstance.class);
  private static final int BOLT_PORT = 7687;
  private static final String IMAGE = "memgraph/memgraph:latest";

  private final GenericContainer<?> container;

  private MemgraphInstance(GenericContainer<?> container) {
    this.container = container;
  }

  /**
   * Starts a Memgraph container and waits until the Bolt port is reachable.
   *
   * @return the running instance
   */
  public static MemgraphInstance start() {
    GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(BOLT_PORT)
            .withCommand("--telemetry-enabled=false")
            .waitingFor(Wait.forListeningPort());
    container.start();
    log.info("Memgraph container started at {}", boltUrlFor(container));
    return new MemgraphInstance(container);
  }

  /** Returns the Bolt URL for this instance. */
  public String getBoltUrl() {
    return boltUrlFor(container);
  }

  @Override
  public void close() {
    container.stop();
    log.info("Memgraph container stopped");
  }

  private static String boltUrlFor(GenericContainer<?> c) {
    return "bolt://" + c.getHost() + ":" + c.getMappedPort(BOLT_PORT);
  }
}
