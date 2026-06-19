package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.JavaTypeNames;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.nio.file.Path;
import java.util.Map;

/**
 * Method node write payload.
 *
 * @author Oleksii Usatov
 */
public record MethodWrite(Path file, Method method) implements BatchWrite {

  @Override
  public Map<String, Object> params() {
    return Map.ofEntries(
        Map.entry(Params.PATH, file.toString()),
        Map.entry(Params.SIG, method.signature()),
        Map.entry(Params.NAME, method.name()),
        Map.entry(Params.RET, method.returnType()),
        Map.entry(Params.IS_STATIC, method.isStatic()),
        Map.entry(Params.VISIBILITY, method.visibility()),
        Map.entry(Params.START, method.startLine()),
        Map.entry(Params.END, method.endLine()),
        Map.entry(Params.OWNER, method.ownerFqn()),
        Map.entry(Params.OWNER_KIND, method.ownerKind()),
        Map.entry(Params.OWNER_DISPLAY_NAME, JavaTypeNames.nameFromFqn(method.ownerFqn())),
        Map.entry(Params.LANGUAGE, method.language()),
        Map.entry(Params.KIND, method.kind()),
        Map.entry(Params.IS_SYNTHETIC, method.isSynthetic()));
  }
}
