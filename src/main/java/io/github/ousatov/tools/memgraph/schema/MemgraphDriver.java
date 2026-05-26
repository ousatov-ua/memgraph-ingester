package io.github.ousatov.tools.memgraph.schema;

import java.util.logging.Level;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Logging;

/**
 * Creates Neo4j Bolt drivers configured for Memgraph CLI use.
 *
 * @author Oleksii Usatov
 */
public final class MemgraphDriver {

  private static final Config QUIET_CONFIG =
      Config.builder().withLogging(Logging.console(Level.WARNING)).build();

  private MemgraphDriver() {
    throw new UnsupportedOperationException("Utility class");
  }

  /** Opens a Bolt driver without enabling Neo4j driver's internal startup logging. */
  public static Driver open(String boltUrl, String user, String pass) {
    return GraphDatabase.driver(boltUrl, AuthTokens.basic(user, pass), QUIET_CONFIG);
  }

  /** Opens a Bolt driver for Memgraph test containers that do not require credentials. */
  public static Driver open(String boltUrl) {
    return open(boltUrl, "", "");
  }
}
