package io.github.ousatov.tools.memgraph.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.cli.CodeEmbeddingCliOptions;
import io.github.ousatov.tools.memgraph.cli.MemoryEmbeddingCliOptions;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EmbeddingSettings}.
 *
 * @author Oleksii Usatov
 */
class CodeEmbeddingSettingsTest {

  @Test
  void disabledUsesPortableDefaults() {
    EmbeddingSettings settings = EmbeddingSettings.disabled();

    assertFalse(settings.enabled());
    assertEquals(EmbeddingSettings.DEFAULT_CODE_INDEX_NAME, settings.indexName());
    assertEquals("memory_chunk_embedding_v2", EmbeddingSettings.DEFAULT_MEMORY_INDEX_NAME);
    assertEquals(EmbeddingSettings.DEFAULT_MODEL_NAME, settings.modelName());
    assertEquals(EmbeddingSettings.DEFAULT_BATCH_SIZE, settings.batchSize());
    assertEquals(EmbeddingSettings.DEFAULT_CHUNK_SIZE, settings.chunkSize());
    assertEquals(EmbeddingSettings.DEFAULT_PROCEDURE_MEMORY_MB, settings.procedureMemoryMb());
    assertFalse(settings.required());
  }

  @Test
  void enabledDefaultsUseSeparateCodeAndMemoryIndexes() {
    EmbeddingSettings code = EmbeddingSettings.codeDefaults();
    EmbeddingSettings memory = EmbeddingSettings.memoryDefaults();

    assertTrue(code.enabled());
    assertTrue(memory.enabled());
    assertEquals(EmbeddingSettings.DEFAULT_CODE_INDEX_NAME, code.indexName());
    assertEquals(EmbeddingSettings.DEFAULT_MEMORY_INDEX_NAME, memory.indexName());
    assertEquals(code.modelName(), memory.modelName());
    assertFalse(code.required());
    assertFalse(memory.required());
  }

  @Test
  void explicitRequiredMemoryEmbeddingsEnableMemoryChunks() {
    EmbeddingSettings settings = new MemoryEmbeddingCliOptions().toSettings(false, true);

    assertTrue(settings.enabled());
    assertTrue(settings.required());
    assertEquals(EmbeddingSettings.DEFAULT_MEMORY_INDEX_NAME, settings.indexName());
  }

  @Test
  void embeddingProcedureMemoryLimitCanBeConfiguredFromCli() {
    CodeEmbeddingCliOptions codeOptions = new CodeEmbeddingCliOptions();
    MemoryEmbeddingCliOptions memoryOptions = new MemoryEmbeddingCliOptions();
    codeOptions.procedureMemoryMb = 1024;
    memoryOptions.procedureMemoryMb = 768;

    EmbeddingSettings code = codeOptions.toSettings(true);
    EmbeddingSettings memory = memoryOptions.toSettings(true, true);

    assertEquals(1024, code.procedureMemoryMb());
    assertEquals(768, memory.procedureMemoryMb());
  }

  @Test
  void nodeSentenceConfigurationEmbedsOnlyChunkText() {
    EmbeddingSettings settings =
        new EmbeddingSettings(
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

    Map<String, Object> config = settings.codeNodeSentenceConfiguration();

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
  void memoryNodeSentenceConfigurationEmbedsOnlyChunkText() {
    EmbeddingSettings settings =
        new EmbeddingSettings(true, "idx", "model", "cos", "f16", 128, 12, "", 0, 0, 0, 0);

    Map<String, Object> config = settings.memoryNodeSentenceConfiguration();

    assertEquals("model", config.get("model_name"));
    assertEquals("embedding", config.get("embedding_property"));
    String excludedProperties = config.get("excluded_properties").toString();
    assertTrue(excludedProperties.contains("project"));
    assertTrue(excludedProperties.contains("sourceLabel"));
    assertTrue(excludedProperties.contains("textHash"));
    assertFalse(excludedProperties.contains("text,"));
    assertFalse((Boolean) config.get("return_embeddings"));
  }

  @Test
  void rejectsInvalidNumericOptions() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EmbeddingSettings(true, "idx", "model", "cos", "f16", -1, 12, "", 0, 0, 0, 0));
  }
}
