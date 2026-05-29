package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.AnnotationNodeWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.AnnotationWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.BatchWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.CallWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.ClassWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.CodeChunkWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.FieldWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.InterfaceWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.MethodWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.PendingCallWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.TypeRelationWrite;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

  void upsertCallsByName(Collection<PendingCallWrite> calls) {
    runBatch(Cypher.CYPHER_UPSERT_CALLS_BY_NAME_BATCH, calls);
  }

  void upsertPendingCallsByName(Collection<PendingCallWrite> calls) {
    runBatch(Cypher.CYPHER_UPSERT_PENDING_CALLS_BY_NAME_BATCH, calls);
  }

  void upsertCodeChunks(Collection<CodeChunkWrite> chunks) {
    runBatch(Cypher.CYPHER_UPSERT_CODE_CHUNKS_BATCH, chunks);
    runBatch(Cypher.CYPHER_LINK_FILE_CODE_CHUNKS_BATCH, chunksForLabel(chunks, Labels.FILE));
    runBatch(Cypher.CYPHER_LINK_CLASS_CODE_CHUNKS_BATCH, chunksForLabel(chunks, Labels.CLASS));
    runBatch(
        Cypher.CYPHER_LINK_INTERFACE_CODE_CHUNKS_BATCH, chunksForLabel(chunks, Labels.INTERFACE));
    runBatch(
        Cypher.CYPHER_LINK_ANNOTATION_CODE_CHUNKS_BATCH, chunksForLabel(chunks, Labels.ANNOTATION));
    runBatch(Cypher.CYPHER_LINK_METHOD_CODE_CHUNKS_BATCH, chunksForLabel(chunks, Labels.METHOD));
    runBatch(Cypher.CYPHER_LINK_FIELD_CODE_CHUNKS_BATCH, chunksForLabel(chunks, Labels.FIELD));
  }

  private void runBatch(String query, Collection<? extends BatchWrite> writes) {
    List<Map<String, Object>> rows = writes.stream().map(BatchWrite::params).toList();
    cypher.runBatch(query, rows);
  }

  private static List<CodeChunkWrite> chunksForLabel(
      Collection<CodeChunkWrite> chunks, String label) {
    return chunks.stream().filter(chunk -> label.equals(chunk.sourceLabel())).toList();
  }
}
