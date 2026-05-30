package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.util.Map;

/**
 * Deferred owner/name call write payload.
 *
 * @author Oleksii Usatov
 */
public record PendingCallWrite(
    String callerSignature, String ownerFqn, String calleeName, int count) implements BatchWrite {

  public PendingCallWrite(String callerSignature, String ownerFqn, String calleeName) {
    this(callerSignature, ownerFqn, calleeName, 1);
  }

  @Override
  public Map<String, Object> params() {
    return Map.of(
        Params.CALLER,
        callerSignature,
        Params.OWNER_FQN,
        ownerFqn,
        Params.CALLEE_NAME,
        calleeName,
        Params.COUNT,
        count);
  }
}
