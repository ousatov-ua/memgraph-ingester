package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.util.Map;

/**
 * Derived code RAG chunk write payload.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
public record CodeChunkWrite(
    String id,
    String sourceLabel,
    String sourceId,
    String language,
    String path,
    String ownerFqn,
    String signature,
    String text,
    String textHash)
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
        Params.LANGUAGE,
        language,
        Params.PATH,
        path,
        Params.OWNER_FQN,
        ownerFqn,
        Params.SIG,
        signature,
        Params.TEXT,
        text,
        Params.TEXT_HASH,
        textHash);
  }
}
