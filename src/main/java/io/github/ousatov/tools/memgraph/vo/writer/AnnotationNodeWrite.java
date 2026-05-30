package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.nio.file.Path;
import java.util.Map;

/**
 * Annotation node write payload.
 *
 * @author Oleksii Usatov
 */
public record AnnotationNodeWrite(
    Path file,
    String pkg,
    String fqn,
    String name,
    String visibility,
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
        Map.entry(Params.VISIBILITY, visibility),
        Map.entry(Params.LANGUAGE, language),
        Map.entry(Params.KIND, kind),
        Map.entry(Params.MODULE_PATH, modulePath),
        Map.entry(Params.FRAMEWORK, framework));
  }
}
