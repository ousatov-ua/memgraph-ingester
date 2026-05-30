package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.nio.file.Path;
import java.util.Map;

/**
 * Class node write payload.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
public record ClassWrite(
    Path file,
    String pkg,
    String fqn,
    String name,
    boolean isAbstract,
    String visibility,
    boolean isEnum,
    boolean isRecord,
    boolean isFinal,
    String language,
    String kind,
    String modulePath,
    String framework)
    implements BatchWrite {

  @Override
  public Map<String, Object> params() {
    return Map.ofEntries(
        Map.entry(Params.PATH, file.toString()),
        Map.entry(Params.PKG, pkg),
        Map.entry(Params.FQN, fqn),
        Map.entry(Params.NAME, name),
        Map.entry(Params.IS_ABSTRACT, isAbstract),
        Map.entry(Params.VISIBILITY, visibility),
        Map.entry(Params.IS_ENUM, isEnum),
        Map.entry(Params.IS_RECORD, isRecord),
        Map.entry(Params.IS_FINAL, isFinal),
        Map.entry(Params.LANGUAGE, language),
        Map.entry(Params.KIND, kind),
        Map.entry(Params.MODULE_PATH, modulePath),
        Map.entry(Params.FRAMEWORK, framework));
  }
}
