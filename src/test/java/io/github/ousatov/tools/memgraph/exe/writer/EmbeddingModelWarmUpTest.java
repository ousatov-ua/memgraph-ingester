package io.github.ousatov.tools.memgraph.exe.writer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GraphWriter#warmUpEmbeddingModel}. Uses a {@code null} session to prove the
 * disabled path performs no Bolt interaction and that any warm-up failure is swallowed instead of
 * failing the ingestion run, returning an empty dimension in both cases.
 *
 * @author Oleksii Usatov
 */
class EmbeddingModelWarmUpTest {

  @Test
  void disabledSettingsDoNotTouchSession() {
    GraphWriter writer = new GraphWriter(null, "test");

    OptionalInt dimension = writer.warmUpEmbeddingModel(EmbeddingSettings.disabled());

    assertTrue(dimension.isEmpty());
  }

  @Test
  void warmUpFailureIsSwallowed() {
    GraphWriter writer = new GraphWriter(null, "test");

    OptionalInt dimension = writer.warmUpEmbeddingModel(enabledSettings("all-MiniLM-L6-v2", 0));

    assertTrue(dimension.isEmpty());
  }

  @Test
  void seedingDimensionDoesNotTouchSession() {
    GraphWriter writer = new GraphWriter(null, "test");

    assertDoesNotThrow(
        () -> writer.seedEmbeddingDimension(enabledSettings("all-MiniLM-L6-v2", 0), 384));
  }

  private static EmbeddingSettings enabledSettings(String modelName, int dimensions) {
    return new EmbeddingSettings(
        true, "idx", modelName, "cos", "f16", 8, 4, "", dimensions, 0, 0, 0, 0, false);
  }
}
