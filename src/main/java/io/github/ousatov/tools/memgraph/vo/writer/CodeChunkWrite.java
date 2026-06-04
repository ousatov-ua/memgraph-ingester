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
    String name,
    String kind,
    String ragRole,
    int startLine,
    int endLine,
    boolean synthetic,
    String text,
    String textHash)
    implements BatchWrite {

  @Override
  public Map<String, Object> params() {
    return Map.ofEntries(
        Map.entry(Params.ID, id),
        Map.entry(Params.SOURCE_LABEL, sourceLabel),
        Map.entry(Params.SOURCE_ID, sourceId),
        Map.entry(Params.LANGUAGE, language),
        Map.entry(Params.PATH, path),
        Map.entry(Params.OWNER_FQN, ownerFqn),
        Map.entry(Params.SIG, signature),
        Map.entry(Params.NAME, name),
        Map.entry(Params.KIND, kind),
        Map.entry(Params.RAG_ROLE, ragRole),
        Map.entry(Params.START_LINE, startLine),
        Map.entry(Params.END_LINE, endLine),
        Map.entry(Params.IS_SYNTHETIC, synthetic),
        Map.entry(Params.TEXT, text),
        Map.entry(Params.TEXT_HASH, textHash));
  }
}
