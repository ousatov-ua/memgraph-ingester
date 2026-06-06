package io.github.ousatov.tools.memgraph.exe.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChunkEmbeddingRefresher}.
 *
 * @author Oleksii Usatov
 */
class ChunkEmbeddingRefresherTest {

  @Test
  void batchCypherKeepsResourceWhenProcedureMemoryLimitIsDisabled() {
    EmbeddingSettings settings = settingsWithProcedureMemory(0);

    String cypher = ChunkEmbeddingRefresher.batchCypher(settings, EmbeddingTarget.CODE);

    assertEquals(EmbeddingTarget.CODE.batchCypher(), cypher);
  }

  @Test
  void batchCypherAddsProcedureMemoryLimitBeforeYield() {
    EmbeddingSettings settings = settingsWithProcedureMemory(1024);

    String cypher = ChunkEmbeddingRefresher.batchCypher(settings, EmbeddingTarget.CODE);

    assertTrue(
        cypher.contains(
            "CALL embeddings.node_sentence(chunks, $config) PROCEDURE MEMORY LIMIT 1024 MB\n"
                + "YIELD success, dimension"));
  }

  private static EmbeddingSettings settingsWithProcedureMemory(int procedureMemoryMb) {
    return new EmbeddingSettings(
        true, "idx", "model", "cos", "f16", 128, 12, "", 0, 0, 0, procedureMemoryMb, 0, true);
  }
}
