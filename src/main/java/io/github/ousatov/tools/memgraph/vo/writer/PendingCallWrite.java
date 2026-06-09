package io.github.ousatov.tools.memgraph.vo.writer;

import io.github.ousatov.tools.memgraph.def.Const.Params;
import java.util.Map;

/**
 * Deferred owner/name call write payload.
 *
 * @author Oleksii Usatov
 */
public record PendingCallWrite(
    String callerSignature, String ownerFqn, String calleeName, int count, boolean allowNameOnly)
    implements BatchWrite {

  public PendingCallWrite(String callerSignature, String ownerFqn, String calleeName) {
    this(callerSignature, ownerFqn, calleeName, 1);
  }

  public PendingCallWrite(String callerSignature, String ownerFqn, String calleeName, int count) {
    this(callerSignature, ownerFqn, calleeName, count, false);
  }

  public static PendingCallWrite allowNameOnly(String callerSignature, String calleeName) {
    return new PendingCallWrite(callerSignature, "", calleeName, 1, true);
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
        count,
        Params.ALLOW_NAME_ONLY,
        allowNameOnly);
  }
}
