package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
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
import java.util.Optional;
import java.util.function.UnaryOperator;
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
    runBatchByOwnerKind(rows, this::fieldBatchCypher);
  }

  void upsertMethodNode(Path file, Method method) {
    upsertMethodNodes(file, List.of(method));
  }

  void upsertMethodNodes(Path file, Collection<Method> methods) {
    List<Map<String, Object>> rows =
        methods.stream().map(method -> new MethodWrite(file, method).params()).toList();
    runBatchByOwnerKind(rows, this::methodBatchCypher);
  }

  void upsertAnnotationReferencesByFqn(Collection<AnnotationWrite> annotations) {
    List<Map<String, Object>> rows = annotations.stream().map(BatchWrite::params).toList();
    runBatchByOwnerKind(rows, this::annotationByFqnBatchCypher);
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
    List<Map<String, Object>> rows = chunks.stream().map(BatchWrite::params).toList();
    cypher.runBatch(Cypher.CYPHER_UPSERT_CODE_CHUNKS_BATCH, rows);
    Map<String, List<Map<String, Object>>> bySourceLabel =
        rows.stream().collect(Collectors.groupingBy(GraphNodeWriter::sourceLabelOf));
    bySourceLabel.forEach(
        (sourceLabel, batchRows) ->
            codeChunkLinkCypher(sourceLabel).ifPresent(query -> cypher.runBatch(query, batchRows)));
  }

  private void runBatch(String query, Collection<? extends BatchWrite> writes) {
    List<Map<String, Object>> rows = writes.stream().map(BatchWrite::params).toList();
    cypher.runBatch(query, rows);
  }

  private void runBatchByOwnerKind(
      List<Map<String, Object>> rows, UnaryOperator<String> queryForKind) {
    Map<String, List<Map<String, Object>>> byKind =
        rows.stream().collect(Collectors.groupingBy(GraphNodeWriter::ownerKindOf));
    byKind.forEach(
        (ownerKind, batchRows) -> cypher.runBatch(queryForKind.apply(ownerKind), batchRows));
  }

  private static String ownerKindOf(Map<String, Object> row) {
    Object ownerKind = row.get(Params.OWNER_KIND);
    return ownerKind instanceof String value && !value.isBlank() ? value : Const.Symbols.EMPTY;
  }

  private static String sourceLabelOf(Map<String, Object> row) {
    Object sourceLabel = row.get(Params.SOURCE_LABEL);
    return sourceLabel instanceof String value && !value.isBlank() ? value : Const.Symbols.EMPTY;
  }

  private String methodBatchCypher(String ownerKind) {
    return switch (ownerKind) {
      case Labels.INTERFACE -> Cypher.CYPHER_UPSERT_INTERFACE_METHODS_BATCH;
      case Labels.ANNOTATION -> Cypher.CYPHER_UPSERT_ANNOTATION_METHODS_BATCH;
      case Labels.CLASS -> Cypher.CYPHER_UPSERT_CLASS_METHODS_BATCH;
      default -> Cypher.CYPHER_UPSERT_METHODS_BATCH;
    };
  }

  private String fieldBatchCypher(String ownerKind) {
    return switch (ownerKind) {
      case Labels.INTERFACE -> Cypher.CYPHER_UPSERT_INTERFACE_FIELDS_BATCH;
      case Labels.ANNOTATION -> Cypher.CYPHER_UPSERT_ANNOTATION_FIELDS_BATCH;
      case Labels.CLASS -> Cypher.CYPHER_UPSERT_CLASS_FIELDS_BATCH;
      default -> Cypher.CYPHER_UPSERT_FIELDS_BATCH;
    };
  }

  private String annotationByFqnBatchCypher(String ownerKind) {
    return switch (ownerKind) {
      case Labels.INTERFACE -> Cypher.CYPHER_UPSERT_INTERFACE_ANNOTATED_WITH_BY_FQN_BATCH;
      case Labels.ANNOTATION -> Cypher.CYPHER_UPSERT_ANNOTATION_ANNOTATED_WITH_BY_FQN_BATCH;
      case Labels.FIELD -> Cypher.CYPHER_UPSERT_FIELD_ANNOTATED_WITH_BY_FQN_BATCH;
      case Labels.CLASS -> Cypher.CYPHER_UPSERT_CLASS_ANNOTATED_WITH_BY_FQN_BATCH;
      default -> Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_FQN_BATCH;
    };
  }

  private Optional<String> codeChunkLinkCypher(String sourceLabel) {
    return switch (sourceLabel) {
      case Labels.FILE -> Optional.of(Cypher.CYPHER_LINK_CODE_CHUNKS_TO_FILES_BATCH);
      case Labels.CLASS -> Optional.of(Cypher.CYPHER_LINK_CODE_CHUNKS_TO_CLASSES_BATCH);
      case Labels.INTERFACE -> Optional.of(Cypher.CYPHER_LINK_CODE_CHUNKS_TO_INTERFACES_BATCH);
      case Labels.ANNOTATION -> Optional.of(Cypher.CYPHER_LINK_CODE_CHUNKS_TO_ANNOTATIONS_BATCH);
      case Labels.METHOD -> Optional.of(Cypher.CYPHER_LINK_CODE_CHUNKS_TO_METHODS_BATCH);
      case Labels.FIELD -> Optional.of(Cypher.CYPHER_LINK_CODE_CHUNKS_TO_FIELDS_BATCH);
      default -> Optional.empty();
    };
  }
}
