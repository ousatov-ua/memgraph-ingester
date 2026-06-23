package io.github.ousatov.tools.memgraph.exe.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import io.github.ousatov.tools.memgraph.vo.Settings;
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

  @Test
  void normalIncrementalPostProcessingUsesDirtyOnlyCodeEmbeddings() {
    assertTrue(IngestionOrchestrator.postProcessingCodeDirtyOnly(Settings.def()));
  }

  @Test
  void codeRagWipeForcesFullPostProcessingCodeEmbeddingRefresh() {
    Settings settings =
        new Settings(
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            EmbeddingSettings.codeDefaults(),
            EmbeddingSettings.disabled());

    assertFalse(IngestionOrchestrator.postProcessingCodeDirtyOnly(settings));
  }

  @Test
  void codeRefResolutionUsesFullRefreshOnlyWhenRunScopeIsUnsafe() {
    assertFalse(IngestionOrchestrator.fullCodeRefRefresh(Settings.def(), false));
    assertTrue(IngestionOrchestrator.fullCodeRefRefresh(Settings.def(), true));
    assertTrue(IngestionOrchestrator.fullCodeRefRefresh(Settings.wipeProjCodeOnly(), false));
    assertTrue(
        IngestionOrchestrator.fullCodeRefRefresh(
            new Settings(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                EmbeddingSettings.disabled(),
                EmbeddingSettings.memoryDefaults().withRequired(true)),
            false));
  }

  private static EmbeddingSettings enabledSettings(String modelName, int dimensions) {
    return new EmbeddingSettings(
        true, "idx", modelName, "cos", "f16", 8, 4, "", dimensions, 0, 0, 0, 0, false);
  }
}
