package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.vo.Method;
import io.github.ousatov.tools.memgraph.vo.writer.AnnotationNodeWrite;
import io.github.ousatov.tools.memgraph.vo.writer.AnnotationWrite;
import io.github.ousatov.tools.memgraph.vo.writer.BatchWrite;
import io.github.ousatov.tools.memgraph.vo.writer.CallWrite;
import io.github.ousatov.tools.memgraph.vo.writer.ClassWrite;
import io.github.ousatov.tools.memgraph.vo.writer.CodeChunkWrite;
import io.github.ousatov.tools.memgraph.vo.writer.FieldWrite;
import io.github.ousatov.tools.memgraph.vo.writer.InterfaceWrite;
import io.github.ousatov.tools.memgraph.vo.writer.MethodWrite;
import io.github.ousatov.tools.memgraph.vo.writer.PendingCallWrite;
import io.github.ousatov.tools.memgraph.vo.writer.TypeRelationWrite;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes homogeneous graph node and edge payloads through batch-capable Cypher statements.
 *
 * @author Oleksii Usatov
 */
final class GraphNodeWriter {

  private final CypherExecutor cypher;

  GraphNodeWriter(CypherExecutor cypher) {
    this.cypher = cypher;
  }

  void upsertClassNodes(Collection<ClassWrite> classes) {
    runBatch(Cypher.CYPHER_UPSERT_CLASSES_BATCH, classes);
  }

  void upsertInterfaceNodes(Collection<InterfaceWrite> interfaces) {
    runBatch(Cypher.CYPHER_UPSERT_INTERFACES_BATCH, interfaces);
  }

  void upsertAnnotationNodes(Collection<AnnotationNodeWrite> annotations) {
    runBatch(Cypher.CYPHER_UPSERT_ANNOTATIONS_BATCH, annotations);
  }

  void upsertClassExtends(Collection<TypeRelationWrite> relations) {
    runBatch(Cypher.CYPHER_UPSERT_EXTENDS_CLASS_BATCH, relations);
  }

  void upsertInterfaceExtends(Collection<TypeRelationWrite> relations) {
    runBatch(Cypher.CYPHER_UPSERT_INTERFACE_EXTENDS_BATCH, relations);
  }

  void upsertImplements(Collection<TypeRelationWrite> relations) {
    runBatch(Cypher.CYPHER_UPSERT_IMPLEMENTS_BATCH, relations);
  }

  void upsertFieldNodes(Path file, Collection<FieldWrite> fields) {
    List<Map<String, Object>> rows =
        fields.stream()
            .map(
                field -> {
                  Map<String, Object> params = new HashMap<>(field.params());
                  params.put(Params.PATH, file.toString());
                  return params;
                })
            .toList();
    cypher.runBatch(Cypher.CYPHER_UPSERT_FIELDS_BATCH, rows);
  }

  void upsertMethodNode(Path file, Method method) {
    upsertMethodNodes(file, List.of(method));
  }

  void upsertMethodNodes(Path file, Collection<Method> methods) {
    List<MethodWrite> writes =
        methods.stream().map(method -> new MethodWrite(file, method)).toList();
    runBatch(Cypher.CYPHER_UPSERT_METHODS_BATCH, writes);
  }

  void upsertAnnotationReferencesByFqn(Collection<AnnotationWrite> annotations) {
    runBatch(Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_FQN_BATCH, annotations);
  }

  void upsertAnnotationReferencesBySig(Collection<AnnotationWrite> annotations) {
    runBatch(Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_SIG_BATCH, annotations);
  }

  void upsertCalls(Collection<CallWrite> calls) {
    runBatch(Cypher.CYPHER_UPSERT_CALLS_BATCH, calls);
  }

  /**
   * Resolves owner/name call records eagerly. Records with an empty owner FQN (unresolvable call
   * scope, e.g. a lambda-parameter receiver) cannot be matched eagerly and are persisted as {@code
   * PendingCall} nodes for post-processing name-only resolution.
   */
  void upsertCallsByName(Collection<PendingCallWrite> calls) {
    Map<Boolean, List<PendingCallWrite>> byUnknownOwner =
        calls.stream().collect(Collectors.partitioningBy(call -> call.ownerFqn().isEmpty()));
    runBatch(Cypher.CYPHER_UPSERT_CALLS_BY_NAME_BATCH, byUnknownOwner.get(false));
    runBatch(Cypher.CYPHER_UPSERT_PENDING_CALLS_BY_NAME_BATCH, byUnknownOwner.get(true));
  }

  void upsertPendingCallsByName(Collection<PendingCallWrite> calls) {
    runBatch(Cypher.CYPHER_UPSERT_PENDING_CALLS_BY_NAME_BATCH, calls);
  }

  void upsertCodeChunks(Collection<CodeChunkWrite> chunks) {
    runBatch(Cypher.CYPHER_UPSERT_CODE_CHUNKS_BATCH, chunks);
  }

  private void runBatch(String query, Collection<? extends BatchWrite> writes) {
    List<Map<String, Object>> rows = writes.stream().map(BatchWrite::params).toList();
    cypher.runBatch(query, rows);
  }
}
