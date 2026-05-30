package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.util.Map;

/**
 * Annotation edge write payload.
 *
 * @author Oleksii Usatov
 */
public record AnnotationWrite(
    String ownerKey, String fqn, String name, String language, String kind) implements BatchWrite {

  @Override
  public Map<String, Object> params() {
    return Map.of(
        Params.OWNER,
        ownerKey,
        Params.SIG,
        ownerKey,
        Params.ANNOT_FQN,
        fqn,
        Params.ANNOT_NAME,
        name,
        Params.LANGUAGE,
        language,
        Params.KIND,
        kind);
  }
}
