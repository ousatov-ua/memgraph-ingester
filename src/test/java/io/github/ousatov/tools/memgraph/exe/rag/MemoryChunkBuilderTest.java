package io.github.ousatov.tools.memgraph.exe.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ousatov.tools.memgraph.vo.rag.MemorySource;
import io.github.ousatov.tools.memgraph.vo.writer.MemoryChunkWrite;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MemoryChunkBuilder}.
 *
 * @author Oleksii Usatov
 */
class MemoryChunkBuilderTest {

  private final MemoryChunkBuilder builder = new MemoryChunkBuilder();

  @Test
  void generatedChunkIdIncludesSourceLabel() {
    MemoryChunkWrite decision = builder.build(memorySource("", "Decision", "shared-id"));
    MemoryChunkWrite task = builder.build(memorySource("", "Task", "shared-id"));

    assertEquals("MCH-Decision-shared-id", decision.id());
    assertEquals("MCH-Task-shared-id", task.id());
  }

  @Test
  void existingChunkIdIsPreserved() {
    MemoryChunkWrite chunk = builder.build(memorySource("MCH-existing", "Decision", "shared-id"));

    assertEquals("MCH-existing", chunk.id());
  }

  private static MemorySource memorySource(String existingChunkId, String sourceLabel, String id) {
    return new MemorySource(
        existingChunkId,
        sourceLabel,
        id,
        "Title",
        "topic",
        "open",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        "",
        List.of());
  }
}
