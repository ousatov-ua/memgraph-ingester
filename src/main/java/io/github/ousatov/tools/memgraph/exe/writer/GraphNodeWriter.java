package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.AnnotationWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.BatchWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.CallWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.CodeChunkWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.FieldWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.MethodWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.PendingCallWrite;
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

  void upsertFieldNodes(Path file, Collection<FieldWrite> fields) {
    fields.forEach(
        field -> {
          Map<String, Object> params = new HashMap<>(field.params());
          params.put(Params.PATH, file.toString());
          cypher.run(Cypher.CYPHER_UPSERT_FIELD, params);
        });
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

  void upsertPendingCallsByName(Collection<PendingCallWrite> calls) {
    runBatch(Cypher.CYPHER_UPSERT_PENDING_CALLS_BY_NAME_BATCH, calls);
  }

  void upsertCodeChunks(Collection<CodeChunkWrite> chunks) {
    runBatch(Cypher.CYPHER_UPSERT_CODE_CHUNKS_BATCH, chunks);
    runBatch(Cypher.CYPHER_LINK_CODE_CHUNKS_BATCH, chunks);
  }

  private void runBatch(String query, Collection<? extends BatchWrite> writes) {
    List<Map<String, Object>> rows = writes.stream().map(BatchWrite::params).toList();
    cypher.runBatch(query, rows);
  }
}
