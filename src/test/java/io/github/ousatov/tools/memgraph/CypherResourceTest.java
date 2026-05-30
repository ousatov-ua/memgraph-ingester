package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ousatov.tools.memgraph.def.Const;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests for CypherResource class.
 *
 * @author Oleksii Usatov
 */
class CypherResourceTest {

  private static String resource(String file) {
    return resourceAt("/io/github/ousatov/tools/memgraph/cypher/" + file);
  }

  private static String resourceAt(String path) {
    try (InputStream in = CypherResourceTest.class.getResourceAsStream(path)) {
      assertNotNull(in, path + " must be present");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError(path + " could not be loaded", e);
    }
  }

  @Test
  void createSchemaContainsMemoryConstraints() {
    String schema = resource("create-schema.cypher");

    assertTrue(schema.contains("CREATE CONSTRAINT ON (l:Language)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (m:Memory)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (d:Decision)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (i:Idea)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (c:Context)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (r:Rule)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (t:Task)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (f:Finding)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (q:Question)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (risk:Risk)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (adr:ADR)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (ref:CodeRef)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (mc:MemoryChunk)"));
    assertTrue(schema.contains("CREATE CONSTRAINT ON (cc:CodeChunk)"));
  }

  @Test
  void createSchemaContainsMemoryIndexes() {
    String schema = resource("create-schema.cypher");

    assertTrue(schema.contains("CREATE INDEX ON :Memory(project)"));
    assertTrue(schema.contains("CREATE INDEX ON :Language(project)"));
    assertTrue(schema.contains("CREATE INDEX ON :Code(language)"));
    assertTrue(schema.contains("CREATE INDEX ON :Decision(status)"));
    assertTrue(schema.contains("CREATE INDEX ON :Decision(topic)"));
    assertTrue(schema.contains("CREATE INDEX ON :Idea(status)"));
    assertTrue(schema.contains("CREATE INDEX ON :Context(updatedAt)"));
    assertTrue(schema.contains("CREATE INDEX ON :Method(ownerFqn)"));
    assertTrue(schema.contains("CREATE INDEX ON :PendingCall(project)"));
    assertTrue(schema.contains("CREATE INDEX ON :Rule(severity)"));
    assertTrue(schema.contains("CREATE INDEX ON :Task(priority)"));
    assertTrue(schema.contains("CREATE INDEX ON :Finding(type)"));
    assertTrue(schema.contains("CREATE INDEX ON :Question(status)"));
    assertTrue(schema.contains("CREATE INDEX ON :Risk(status)"));
    assertTrue(schema.contains("CREATE INDEX ON :ADR(status)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeRef(targetType)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeRef(key)"));
    assertTrue(schema.contains("CREATE INDEX ON :MemoryChunk(project)"));
    assertTrue(schema.contains("CREATE INDEX ON :MemoryChunk(sourceLabel)"));
    assertTrue(schema.contains("CREATE INDEX ON :MemoryChunk(sourceId)"));
    assertTrue(schema.contains("CREATE INDEX ON :MemoryChunk(textHash)"));
    assertTrue(schema.contains("CREATE INDEX ON :MemoryChunk(embeddingModel)"));
    assertTrue(schema.contains("CREATE INDEX ON :MemoryChunk(embeddingDimensions)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeChunk(project)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeChunk(sourceLabel)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeChunk(sourceId)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeChunk(textHash)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeChunk(embeddingModel)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeChunk(embeddingDimensions)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeChunk(language)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeChunk(path)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeChunk(ownerFqn)"));
    assertTrue(schema.contains("CREATE INDEX ON :CodeChunk(signature)"));
  }

  @Test
  void dropSchemaContainsChunkConstraintsAndIndexes() {
    String schema = resource("drop-schema.cypher");

    assertTrue(schema.contains("DROP CONSTRAINT ON (mc:MemoryChunk)"));
    assertTrue(schema.contains("DROP CONSTRAINT ON (cc:CodeChunk)"));
    assertTrue(schema.contains("DROP INDEX ON :MemoryChunk(project)"));
    assertTrue(schema.contains("DROP INDEX ON :MemoryChunk(sourceId)"));
    assertTrue(schema.contains("DROP INDEX ON :CodeChunk(project)"));
    assertTrue(schema.contains("DROP INDEX ON :CodeChunk(signature)"));
  }

  @Test
  void wipeProjectCodeResourcePreservesMemoryLabels() {
    String cypher = Const.Cypher.CYPHER_WIPE_PROJECT_CODE;

    assertTrue(cypher.contains("n:Code"));
    assertTrue(cypher.contains("n:Language"));
    assertTrue(cypher.contains("n:Package"));
    assertTrue(cypher.contains("n:File"));
    assertTrue(cypher.contains("n:Class"));
    assertTrue(cypher.contains("n:Interface"));
    assertTrue(cypher.contains("n:Annotation"));
    assertTrue(cypher.contains("n:Method"));
    assertTrue(cypher.contains("n:Field"));
    assertTrue(cypher.contains("n:PendingCall"));
    assertTrue(cypher.contains("n:CodeChunk"));
    assertFalse(cypher.contains("n:Memory"));
    assertFalse(cypher.contains("n:Decision"));
    assertFalse(cypher.contains("n:CodeRef"));
    assertFalse(cypher.contains("n:MemoryChunk"));
  }

  @Test
  void wipeProjectMemoriesResourcePreservesCodeLabels() {
    String cypher = Const.Cypher.CYPHER_WIPE_PROJECT_MEMORIES;

    assertTrue(cypher.contains("n:Memory"));
    assertTrue(cypher.contains("n:Decision"));
    assertTrue(cypher.contains("n:Idea"));
    assertTrue(cypher.contains("n:Context"));
    assertTrue(cypher.contains("n:Rule"));
    assertTrue(cypher.contains("n:Task"));
    assertTrue(cypher.contains("n:Finding"));
    assertTrue(cypher.contains("n:Question"));
    assertTrue(cypher.contains("n:Risk"));
    assertTrue(cypher.contains("n:ADR"));
    assertTrue(cypher.contains("n:CodeRef"));
    assertTrue(cypher.contains("n:MemoryChunk"));
    assertFalse(cypher.matches("(?s).*\\bn:Code(?!Ref)\\b.*"));
    assertFalse(cypher.contains("n:CodeChunk"));
    assertFalse(cypher.contains("n:Language"));
    assertFalse(cypher.contains("n:Class"));
  }

  @Test
  void codeChunkRefreshResourcesPreserveEmbeddingOnlyWhenTextHashMatches() {
    String upsert = Const.Cypher.CYPHER_UPSERT_CODE_CHUNKS_BATCH;
    String callsByName = Const.Cypher.CYPHER_UPSERT_CALLS_BY_NAME_BATCH;
    String linkFile = Const.Cypher.CYPHER_LINK_FILE_CODE_CHUNKS_BATCH;
    String linkMethod = Const.Cypher.CYPHER_LINK_METHOD_CODE_CHUNKS_BATCH;
    String deleteMissing = Const.Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILES;
    String pathsMissingCodeChunks = Const.Cypher.CYPHER_GET_FILE_PATHS_MISSING_CODE_CHUNKS;
    String wipeCodeRag = Const.Cypher.CYPHER_WIPE_CODE_RAG_BATCH;
    String modelInfo = Const.Cypher.CYPHER_CODE_EMBEDDING_MODEL_INFO;
    String createIndex = Const.Cypher.CYPHER_CREATE_CODE_CHUNK_VECTOR_INDEX;
    String showIndex = Const.Cypher.CYPHER_SHOW_VECTOR_INDEX_INFO;
    String countChunks = Const.Cypher.CYPHER_COUNT_CODE_CHUNKS;
    String countDirtyEmbeddings = Const.Cypher.CYPHER_COUNT_DIRTY_CODE_CHUNK_EMBEDDINGS;
    String markStaleEmbeddings = Const.Cypher.CYPHER_MARK_STALE_CODE_CHUNK_EMBEDDINGS;
    String refreshEmbeddings = Const.Cypher.CYPHER_REFRESH_CODE_CHUNK_EMBEDDING_BATCH;
    String updateEmbeddingMetadata = Const.Cypher.CYPHER_UPDATE_CODE_CHUNK_EMBEDDING_METADATA;
    String failureDetail = Const.Cypher.CYPHER_GET_CODE_CHUNK_EMBEDDING_FAILURE_DETAIL;
    String createMemoryIndex = Const.Cypher.CYPHER_CREATE_MEMORY_CHUNK_VECTOR_INDEX;
    String countMemoryChunks = Const.Cypher.CYPHER_COUNT_MEMORY_CHUNKS;
    String listMemorySources = Const.Cypher.CYPHER_LIST_MEMORY_CHUNK_SOURCES;
    String deleteStaleMemoryChunks = Const.Cypher.CYPHER_DELETE_STALE_MEMORY_CHUNKS;
    String wipeMemoryRag = Const.Cypher.CYPHER_WIPE_MEMORY_RAG_BATCH;
    String upsertMemoryChunks = Const.Cypher.CYPHER_UPSERT_MEMORY_CHUNKS_BATCH;
    String markStaleMemoryEmbeddings = Const.Cypher.CYPHER_MARK_STALE_MEMORY_CHUNK_EMBEDDINGS;
    String refreshMemoryEmbeddings = Const.Cypher.CYPHER_REFRESH_MEMORY_CHUNK_EMBEDDING_BATCH;
    String updateMemoryEmbeddingMetadata =
        Const.Cypher.CYPHER_UPDATE_MEMORY_CHUNK_EMBEDDING_METADATA;
    String memoryFailureDetail = Const.Cypher.CYPHER_GET_MEMORY_CHUNK_EMBEDDING_FAILURE_DETAIL;

    assertTrue(upsert.contains("MERGE (chunk:CodeChunk"));
    assertTrue(upsert.contains("chunk.textHash AS previousTextHash"));
    assertTrue(upsert.contains("previousTextHash <> row.textHash"));
    assertTrue(upsert.contains("REMOVE chunk.embedding"));
    assertTrue(upsert.contains("SET chunk.embeddingDirty = true"));
    assertTrue(callsByName.contains("UNWIND $rows AS row"));
    assertTrue(callsByName.contains("row.caller AS callerSignature"));
    assertTrue(callsByName.contains("MERGE (caller)-[call:CALLS]->(callee)"));
    assertTrue(callsByName.contains("SET call.count = coalesce(call.count, 0) + callCount"));
    assertTrue(linkFile.contains("MATCH (source:File"));
    assertTrue(linkFile.contains("MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)"));
    assertTrue(linkMethod.contains("MATCH (source:Method"));
    assertTrue(linkMethod.contains("MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)"));

    assertTrue(Const.Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILE.contains("chunk:CodeChunk"));
    assertTrue(deleteMissing.contains("chunk.path STARTS WITH $sourceRootPrefix"));
    assertTrue(deleteMissing.contains("NOT chunk.path IN $paths"));
    assertTrue(pathsMissingCodeChunks.contains("OPTIONAL MATCH (chunk:CodeChunk"));
    assertTrue(pathsMissingCodeChunks.contains("WHERE chunkCount = 0"));
    assertTrue(Const.Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILE_EXCEPT.contains("chunk.id IN $ids"));
    assertTrue(wipeCodeRag.contains("MATCH (chunk:CodeChunk {project: $project})"));
    assertTrue(wipeCodeRag.contains("DETACH DELETE chunk"));
    assertTrue(wipeCodeRag.contains("RETURN count(chunk) AS deleted"));
    assertTrue(modelInfo.contains("CALL embeddings.model_info($config)"));
    assertTrue(createIndex.contains("CREATE VECTOR INDEX __INDEX_NAME__"));
    assertTrue(showIndex.contains("SHOW VECTOR INDEX INFO"));
    assertTrue(countChunks.contains("RETURN count(chunk) AS count"));
    assertFalse(countChunks.contains("{project: $project}"));
    assertTrue(countDirtyEmbeddings.contains("embeddingDirty: true"));
    assertTrue(countDirtyEmbeddings.contains("RETURN count(chunk) AS count"));
    assertTrue(markStaleEmbeddings.contains("RETURN count(chunk) AS count"));
    assertTrue(markStaleEmbeddings.contains("chunk.embeddingModel <> $modelName"));
    assertTrue(markStaleEmbeddings.contains("SET chunk.embeddingDirty = true"));
    assertTrue(refreshEmbeddings.contains("CALL embeddings.node_sentence(chunks, $config)"));
    assertTrue(refreshEmbeddings.contains("embeddingDirty: true"));
    assertTrue(refreshEmbeddings.contains("ORDER BY chunk.id"));
    assertTrue(refreshEmbeddings.contains("RETURN success AS success"));
    assertTrue(updateEmbeddingMetadata.contains("SET chunk.embeddingModel = $modelName"));
    assertTrue(updateEmbeddingMetadata.contains("chunk.embeddingDirty = false"));
    assertTrue(failureDetail.contains("substring(chunk.text, 0, 240) AS preview"));
    assertTrue(createMemoryIndex.contains("CREATE VECTOR INDEX __INDEX_NAME__"));
    assertTrue(createMemoryIndex.contains("ON :MemoryChunk"));
    assertTrue(countMemoryChunks.contains("MATCH (chunk:MemoryChunk"));
    assertFalse(countMemoryChunks.contains("{project: $project}"));
    assertTrue(listMemorySources.contains("MATCH (root:Memory"));
    assertTrue(listMemorySources.contains("HAS_RAG_CHUNK"));
    assertTrue(deleteStaleMemoryChunks.contains("DETACH DELETE chunk"));
    assertTrue(deleteStaleMemoryChunks.contains("row.sourceLabel = chunk.sourceLabel"));
    assertTrue(wipeMemoryRag.contains("MATCH (chunk:MemoryChunk {project: $project})"));
    assertTrue(wipeMemoryRag.contains("DETACH DELETE chunk"));
    assertTrue(wipeMemoryRag.contains("RETURN count(chunk) AS deleted"));
    assertTrue(upsertMemoryChunks.contains("MERGE (chunk:MemoryChunk"));
    assertTrue(upsertMemoryChunks.contains("previousTextHash <> row.textHash"));
    assertTrue(upsertMemoryChunks.contains("REMOVE chunk.embedding"));
    assertTrue(upsertMemoryChunks.contains("SET chunk.embeddingDirty = true"));
    assertTrue(upsertMemoryChunks.contains("MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)"));
    assertTrue(markStaleMemoryEmbeddings.contains("chunk.embeddingModel <> $modelName"));
    assertTrue(markStaleMemoryEmbeddings.contains("SET chunk.embeddingDirty = true"));
    assertTrue(refreshMemoryEmbeddings.contains("CALL embeddings.node_sentence(chunks, $config)"));
    assertTrue(refreshMemoryEmbeddings.contains("embeddingDirty: true"));
    assertTrue(refreshMemoryEmbeddings.contains("ORDER BY chunk.id"));
    assertTrue(updateMemoryEmbeddingMetadata.contains("SET chunk.embeddingModel = $modelName"));
    assertTrue(updateMemoryEmbeddingMetadata.contains("chunk.embeddingDirty = false"));
    assertTrue(memoryFailureDetail.contains("substring(chunk.text, 0, 240) AS preview"));
  }

  @Test
  void nativeImageResourceConfigIncludesCypherResources() {
    String config =
        resourceAt(
            "/META-INF/native-image/io.github.ousatov-ua/memgraph-ingester/resource-config.json");

    assertTrue(config.contains(".*[.]cypher$"));
    assertTrue(config.contains("simplelogger[.]properties$"));
    assertTrue(config.contains("io/github/ousatov/tools/memgraph/js/js-(.*)[.]cjs$"));
    assertTrue(config.contains("AI-memgraph-(code|memory)(-no-mcp)?-template[.]md$"));
  }

  @Test
  void nativeImageReflectConfigIncludesJavaParserAstFields() {
    String config =
        resourceAt(
            "/META-INF/native-image/io.github.ousatov-ua/memgraph-ingester/reflect-config.json");

    assertTrue(
        config.contains("\"name\": \"com.github.javaparser.ast.expr.VariableDeclarationExpr\""));
    assertTrue(config.contains("\"allDeclaredFields\": true"));
  }
}
