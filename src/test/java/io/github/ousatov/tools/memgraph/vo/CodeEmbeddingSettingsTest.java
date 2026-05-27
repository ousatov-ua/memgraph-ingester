package io.github.ousatov.tools.memgraph.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CodeEmbeddingSettings}.
 *
 * @author Oleksii Usatov
 */
class CodeEmbeddingSettingsTest {

  @Test
  void disabledUsesPortableDefaults() {
    CodeEmbeddingSettings settings = CodeEmbeddingSettings.disabled();

    assertFalse(settings.enabled());
    assertEquals(CodeEmbeddingSettings.DEFAULT_INDEX_NAME, settings.indexName());
    assertEquals(CodeEmbeddingSettings.DEFAULT_MODEL_NAME, settings.modelName());
    assertEquals(CodeEmbeddingSettings.DEFAULT_BATCH_SIZE, settings.batchSize());
    assertEquals(CodeEmbeddingSettings.DEFAULT_CHUNK_SIZE, settings.chunkSize());
  }

  @Test
  void nodeSentenceConfigurationEmbedsOnlyChunkText() {
    CodeEmbeddingSettings settings =
        new CodeEmbeddingSettings(
            true,
            "idx",
            "openai/text-embedding-3-small",
            "cos",
            "f16",
            128,
            12,
            "cuda:0",
            768,
            64,
            8,
            4096);

    Map<String, Object> config = settings.nodeSentenceConfiguration();

    assertEquals("openai/text-embedding-3-small", config.get("model_name"));
    assertEquals("embedding", config.get("embedding_property"));
    assertTrue(config.get("excluded_properties").toString().contains("project"));
    assertTrue(config.get("excluded_properties").toString().contains("textHash"));
    assertFalse((Boolean) config.get("return_embeddings"));
    assertEquals("cuda:0", config.get("device"));
    assertEquals(768, config.get("dimensions"));
    assertEquals(64, config.get("remote_batch_size"));
    assertEquals(8, config.get("concurrency"));
  }

  @Test
  void rejectsInvalidNumericOptions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CodeEmbeddingSettings(true, "idx", "model", "cos", "f16", -1, 12, "", 0, 0, 0, 0));
  }
}
