package io.github.ousatov.tools.memgraph.extension;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension that starts a shared Memgraph instance once per test suite run.
 *
 * <p>The instance is stored at the root extension context so it is created once regardless of how
 * many test classes use it, and destroyed automatically after all tests complete (via {@link
 * ExtensionContext.Store.CloseableResource}).
 *
 * <p>If Memgraph cannot be started, the entire test class is aborted (skipped) rather than failed.
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
    try {
      ctx.getRoot()
          .getStore(NS)
          .getOrComputeIfAbsent(
              KEY,
              _ -> {
                try {
                  return MemgraphInstance.start();
                } catch (Exception e) {
                  throw new ProcessingException(e);
                }
              },
              MemgraphInstance.class);
    } catch (Exception e) {
      Assumptions.abort("Memgraph unavailable — skipping integration tests: " + e.getMessage());
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
