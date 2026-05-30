package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.util.Map;

/**
 * Resolved call edge write payload.
 *
 * @author Oleksii Usatov
 */
public record CallWrite(String callerSignature, String calleeSignature, int count)
    implements BatchWrite {

  public CallWrite(String callerSignature, String calleeSignature) {
    this(callerSignature, calleeSignature, 1);
  }

  @Override
  public Map<String, Object> params() {
    return Map.of(
        Params.CALLER, callerSignature, Params.CALLEE, calleeSignature, Params.COUNT, count);
  }
}
