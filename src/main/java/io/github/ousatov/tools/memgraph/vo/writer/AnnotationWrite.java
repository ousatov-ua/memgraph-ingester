package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.util.Map;

/**
 * Annotation edge write payload.
 *
 * @author Oleksii Usatov
 */
public record AnnotationWrite(
    String ownerKey, String fqn, String name, String language, String kind, String ownerKind)
    implements BatchWrite {

  public AnnotationWrite(String ownerKey, String fqn, String name, String language, String kind) {
    this(ownerKey, fqn, name, language, kind, Const.Symbols.EMPTY);
  }

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
        kind,
        Params.OWNER_KIND,
        ownerKind);
  }
}
