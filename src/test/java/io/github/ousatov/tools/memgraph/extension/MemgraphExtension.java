package io.github.ousatov.tools.memgraph.extension;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.DockerClientFactory;

/**
 * JUnit 5 extension that starts a shared Memgraph container once per test suite run.
 *
 * <p>The container is stored at the root extension context so it is created once regardless of how
 * many test classes use it, and destroyed automatically after all tests complete (via {@link
 * ExtensionContext.Store.CloseableResource}).
 *
 * <p>Tests are skipped (aborted) when Docker is unavailable or the container fails to start.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @ExtendWith(MemgraphExtension.class)
 * class MyIT {
 *   @BeforeAll
 *   static void setup(MemgraphInstance mg) { ... }
 * }
 * }</pre>
 *
 * @author Oleksii Usatov
 */
public final class MemgraphExtension implements BeforeAllCallback, ParameterResolver {

  private static final Namespace NS = Namespace.create(MemgraphExtension.class);
  private static final String KEY = "instance";

  @Override
  public void beforeAll(ExtensionContext ctx) {
    if (!DockerClientFactory.instance().isDockerAvailable()) {
      Assumptions.abort("Docker is not available — skipping integration tests");
    }
    try {
      ctx.getRoot()
          .getStore(NS)
          .getOrComputeIfAbsent(KEY, _ -> MemgraphInstance.start(), MemgraphInstance.class);
    } catch (Exception e) {
      Assumptions.abort("Memgraph container failed to start — skipping: " + e.getMessage());
    }
  }

  @Override
  public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
    return pc.getParameter().getType() == MemgraphInstance.class;
  }

  @Override
  public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
    MemgraphInstance inst = ec.getRoot().getStore(NS).get(KEY, MemgraphInstance.class);
    if (inst == null) {
      throw new IllegalStateException("MemgraphInstance not initialized");
    }
    return inst;
  }
}
