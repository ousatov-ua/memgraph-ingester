package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.util.Map;

/**
 * Derived memory RAG chunk write payload.
 *
 * @author Oleksii Usatov
 */
public record MemoryChunkWrite(
    String id, String sourceLabel, String sourceId, String text, String textHash)
    implements BatchWrite {

  @Override
  public Map<String, Object> params() {
    return Map.of(
        Params.ID,
        id,
        Params.SOURCE_LABEL,
        sourceLabel,
        Params.SOURCE_ID,
        sourceId,
        Params.TEXT,
        text,
        Params.TEXT_HASH,
        textHash);
  }
}
