package io.github.ousatov.tools.memgraph.exe.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IngestionOrchestrator#embeddingWarmupCandidates}: only enabled settings
 * qualify, and candidates are deduplicated by model name and dimensions so one model is warmed once
 * even when code and memory embeddings share it.
 *
 * @author Oleksii Usatov
 */
class EmbeddingWarmupCandidatesTest {

  @Test
  void disabledSettingsYieldNoCandidates() {
    List<EmbeddingSettings> candidates =
        IngestionOrchestrator.embeddingWarmupCandidates(
            EmbeddingSettings.disabled(), EmbeddingSettings.disabled());

    assertTrue(candidates.isEmpty());
  }

  @Test
  void nullSettingsAreTolerated() {
    List<EmbeddingSettings> candidates =
        IngestionOrchestrator.embeddingWarmupCandidates(null, EmbeddingSettings.disabled());

    assertTrue(candidates.isEmpty());
  }

  @Test
  void sameModelAndDimensionsDeduplicateToOneCandidate() {
    List<EmbeddingSettings> candidates =
        IngestionOrchestrator.embeddingWarmupCandidates(
            enabledSettings("all-MiniLM-L6-v2", 0), enabledSettings("all-MiniLM-L6-v2", 0));

    assertEquals(1, candidates.size());
  }

  @Test
  void differentModelsAreKeptSeparately() {
    List<EmbeddingSettings> candidates =
        IngestionOrchestrator.embeddingWarmupCandidates(
            enabledSettings("all-MiniLM-L6-v2", 0), enabledSettings("other-model", 384));

    assertEquals(2, candidates.size());
  }

  private static EmbeddingSettings enabledSettings(String modelName, int dimensions) {
    return new EmbeddingSettings(
        true, "idx", modelName, "cos", "f16", 8, 4, "", dimensions, 0, 0, 0, 0, false);
  }
}
