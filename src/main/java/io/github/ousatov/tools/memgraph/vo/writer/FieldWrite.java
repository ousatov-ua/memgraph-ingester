package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.util.Map;

/**
 * Field node write payload.
 *
 * @author Oleksii Usatov
 */
public record FieldWrite(
    String ownerFqn,
    String fqn,
    String name,
    String type,
    boolean isStatic,
    String visibility,
    String language,
    String kind,
    String ownerKind)
    implements BatchWrite {

  public FieldWrite(
      String ownerFqn,
      String fqn,
      String name,
      String type,
      boolean isStatic,
      String visibility,
      String language,
      String kind) {
    this(ownerFqn, fqn, name, type, isStatic, visibility, language, kind, Const.Symbols.EMPTY);
  }

  @Override
  public Map<String, Object> params() {
    return Map.of(
        Params.FQN,
        fqn,
        Params.NAME,
        name,
        Params.TYPE,
        type,
        Params.IS_STATIC,
        isStatic,
        Params.VISIBILITY,
        visibility,
        Params.LANGUAGE,
        language,
        Params.KIND,
        kind,
        Params.OWNER,
        ownerFqn,
        Params.OWNER_KIND,
        ownerKind);
  }
}
