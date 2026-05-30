package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.JavaTypeNames;
import java.util.Map;

/**
 * Type relationship write payload.
 *
 * @author Oleksii Usatov
 */
public record TypeRelationWrite(
    String childFqn, String targetFqn, String targetName, String targetPkg, String language)
    implements BatchWrite {

  public TypeRelationWrite(String childFqn, String targetFqn, String language) {
    this(
        childFqn,
        targetFqn,
        JavaTypeNames.nameFromFqn(targetFqn),
        JavaTypeNames.packageFromFqn(targetFqn),
        language);
  }

  @Override
  public Map<String, Object> params() {
    return Map.of(
        Params.CHILD,
        childFqn,
        Params.TARGET,
        targetFqn,
        Params.TARGET_NAME,
        targetName,
        Params.TARGET_PKG,
        targetPkg,
        Params.LANGUAGE,
        language);
  }
}
