package io.github.ousatov.tools.memgraph.exe.writer;

import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.vo.EmbeddingSettings;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Per-chunk-type constants distinguishing {@code :CodeChunk} from {@code :MemoryChunk} operations.
 *
 * @author Oleksii Usatov
 */
record EmbeddingTarget(
    String chunkLabel,
    String indexNameParam,
    String createVectorIndexTemplate,
    String countChunksCypher,
    String countStaleCypher,
    /** {@code null} when the chunk type does not support dirty-only refresh. */
    @Nullable String countDirtyCypher,
    String batchCypher,
    String updateMetadataCypher,
    String failureDetailCypher,
    boolean includePathInFailureDetail,
    Function<EmbeddingSettings, Map<String, Object>> nodeConfigExtractor) {

  static final EmbeddingTarget CODE =
      new EmbeddingTarget(
          "CodeChunk",
          Params.CODE_EMBEDDING_INDEX_NAME,
          Cypher.CYPHER_CREATE_CODE_CHUNK_VECTOR_INDEX,
          Cypher.CYPHER_COUNT_CODE_CHUNKS,
          Cypher.CYPHER_MARK_STALE_CODE_CHUNK_EMBEDDINGS,
          Cypher.CYPHER_COUNT_DIRTY_CODE_CHUNK_EMBEDDINGS,
          Cypher.CYPHER_REFRESH_CODE_CHUNK_EMBEDDING_BATCH,
          Cypher.CYPHER_UPDATE_CODE_CHUNK_EMBEDDING_METADATA,
          Cypher.CYPHER_GET_CODE_CHUNK_EMBEDDING_FAILURE_DETAIL,
          true,
          EmbeddingSettings::codeNodeSentenceConfiguration);

  static final EmbeddingTarget MEMORY =
      new EmbeddingTarget(
          "MemoryChunk",
          Params.MEMORY_EMBEDDING_INDEX_NAME,
          Cypher.CYPHER_CREATE_MEMORY_CHUNK_VECTOR_INDEX,
          Cypher.CYPHER_COUNT_MEMORY_CHUNKS,
          Cypher.CYPHER_MARK_STALE_MEMORY_CHUNK_EMBEDDINGS,
          null,
          Cypher.CYPHER_REFRESH_MEMORY_CHUNK_EMBEDDING_BATCH,
          Cypher.CYPHER_UPDATE_MEMORY_CHUNK_EMBEDDING_METADATA,
          Cypher.CYPHER_GET_MEMORY_CHUNK_EMBEDDING_FAILURE_DETAIL,
          false,
          EmbeddingSettings::memoryNodeSentenceConfiguration);
}
