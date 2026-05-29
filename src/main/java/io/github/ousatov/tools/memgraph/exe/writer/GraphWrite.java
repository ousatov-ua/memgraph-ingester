package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.JavaTypeNames;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.nio.file.Path;
import java.util.Map;

/**
 * Shared graph write payloads for homogeneous Cypher batches.
 *
 * @author Oleksii Usatov
 */
public final class GraphWrite {

  private GraphWrite() {
    // Utility class.
  }

  /** Shared graph write payload contract for homogeneous Cypher batches. */
  public interface BatchWrite {

    Map<String, Object> params();
  }

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

  /**
   * Interface node write payload.
   *
   * @author Oleksii Usatov
   */
  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
  public record InterfaceWrite(
      Path file,
      String pkg,
      String fqn,
      String name,
      boolean isAbstract,
      String visibility,
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
          Map.entry(Params.IS_FINAL, isFinal),
          Map.entry(Params.LANGUAGE, language),
          Map.entry(Params.KIND, kind),
          Map.entry(Params.MODULE_PATH, modulePath),
          Map.entry(Params.FRAMEWORK, framework));
    }
  }

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

  /** Field node write payload. */
  public record FieldWrite(
      String ownerFqn,
      String fqn,
      String name,
      String type,
      boolean isStatic,
      String visibility,
      String language,
      String kind)
      implements BatchWrite {

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
          ownerFqn);
    }
  }

  /** Derived code RAG chunk write payload. */
  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
  public record CodeChunkWrite(
      String id,
      String sourceLabel,
      String sourceId,
      String language,
      String path,
      String ownerFqn,
      String signature,
      String text,
      String textHash)
      implements BatchWrite {

    @Override
    public Map<String, Object> params() {
      return Map.of(
          Params.ID,
          id,
          Params.SOURCE_LABEL,
          sourceLabel,
          Params.SOURCE_ID,
          sourceId,
          Params.LANGUAGE,
          language,
          Params.PATH,
          path,
          Params.OWNER_FQN,
          ownerFqn,
          Params.SIG,
          signature,
          Params.TEXT,
          text,
          Params.TEXT_HASH,
          textHash);
    }
  }

  /** Derived memory RAG chunk write payload. */
  public record MemoryChunkWrite(
      String id, String sourceLabel, String sourceId, String text, String textHash)
      implements BatchWrite {

    @Override
    public Map<String, Object> params() {
      return Map.of(
          Params.ID,
          id,
          Params.SOURCE_LABEL,
          sourceLabel,
          Params.SOURCE_ID,
          sourceId,
          Params.TEXT,
          text,
          Params.TEXT_HASH,
          textHash);
    }
  }

  /** Method node write payload. */
  record MethodWrite(Path file, Method method) implements BatchWrite {

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
          Map.entry(Params.OWNER_DISPLAY_NAME, JavaTypeNames.nameFromFqn(method.ownerFqn())),
          Map.entry(Params.LANGUAGE, method.language()),
          Map.entry(Params.KIND, method.kind()),
          Map.entry(Params.IS_SYNTHETIC, method.isSynthetic()));
    }
  }

  /** Annotation edge write payload. */
  public record AnnotationWrite(
      String ownerKey, String fqn, String name, String language, String kind)
      implements BatchWrite {

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

  /** Resolved call edge write payload. */
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

  /** Deferred owner/name call write payload. */
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
}
