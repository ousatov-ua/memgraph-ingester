package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  private static int occurrences(String text, String needle) {
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count++;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }

  private static void assertContainsAll(String text, String... snippets) {
    for (String snippet : snippets) {
      assertTrue(text.contains(snippet), () -> "Expected resource to contain: " + snippet);
    }
  }

  /**
   * Memgraph rejects any clause except RETURN after a writeable procedure call, so an embedding
   * batch query must end with YIELD followed directly by RETURN after the {@code
   * embeddings.node_sentence} call.
   */
  private static void assertEndsWithReturnAfterNodeSentence(String cypher) {
    int callIndex = cypher.indexOf("CALL embeddings.node_sentence");
    assertTrue(callIndex >= 0, "Expected an embeddings.node_sentence call");
    String afterCall = cypher.substring(callIndex).strip();
    assertTrue(
        afterCall.matches(
            "(?s)CALL embeddings\\.node_sentence\\([^)]*\\)\\s+YIELD [^\\n]*\\s+RETURN [^\\n]*"),
        () ->
            "Only YIELD and RETURN may follow the writeable node_sentence call, but was:\n"
                + afterCall);
  }

  @Test
  void createSchemaContainsMemoryConstraints() {
    String schema = resource("create-schema.cypher");

    assertContainsAll(
        schema,
        "CREATE CONSTRAINT ON (l:Language)",
        "CREATE CONSTRAINT ON (m:Memory)",
        "CREATE CONSTRAINT ON (d:Decision)",
        "CREATE CONSTRAINT ON (i:Idea)",
        "CREATE CONSTRAINT ON (c:Context)",
        "CREATE CONSTRAINT ON (r:Rule)",
        "CREATE CONSTRAINT ON (t:Task)",
        "CREATE CONSTRAINT ON (f:Finding)",
        "CREATE CONSTRAINT ON (q:Question)",
        "CREATE CONSTRAINT ON (risk:Risk)",
        "CREATE CONSTRAINT ON (adr:ADR)",
        "CREATE CONSTRAINT ON (ref:CodeRef)",
        "CREATE CONSTRAINT ON (mc:MemoryChunk)",
        "CREATE CONSTRAINT ON (cc:CodeChunk)");
  }

  @Test
  void createSchemaContainsMemoryIndexes() {
    String schema = resource("create-schema.cypher");

    assertContainsAll(
        schema,
        "CREATE INDEX ON :Memory(project)",
        "CREATE INDEX ON :Language(project)",
        "CREATE INDEX ON :Code(language)",
        "CREATE INDEX ON :Decision(status)",
        "CREATE INDEX ON :Decision(topic)",
        "CREATE INDEX ON :Idea(status)",
        "CREATE INDEX ON :Context(updatedAt)",
        "CREATE INDEX ON :Method(ownerFqn)",
        "CREATE INDEX ON :PendingCall(project)",
        "CREATE INDEX ON :Rule(severity)",
        "CREATE INDEX ON :Task(priority)",
        "CREATE INDEX ON :Finding(type)",
        "CREATE INDEX ON :Question(status)",
        "CREATE INDEX ON :Risk(status)",
        "CREATE INDEX ON :ADR(status)",
        "CREATE INDEX ON :CodeRef(targetType)",
        "CREATE INDEX ON :CodeRef(key)",
        "CREATE INDEX ON :MemoryChunk(project)",
        "CREATE INDEX ON :MemoryChunk(sourceLabel)",
        "CREATE INDEX ON :MemoryChunk(sourceId)",
        "CREATE INDEX ON :MemoryChunk(textHash)",
        "CREATE INDEX ON :MemoryChunk(embeddingModel)",
        "CREATE INDEX ON :MemoryChunk(embeddingDimensions)",
        "CREATE INDEX ON :CodeChunk(project)",
        "CREATE INDEX ON :CodeChunk(sourceLabel)",
        "CREATE INDEX ON :CodeChunk(sourceId)",
        "CREATE INDEX ON :CodeChunk(textHash)",
        "CREATE INDEX ON :CodeChunk(embeddingModel)",
        "CREATE INDEX ON :CodeChunk(embeddingDimensions)",
        "CREATE INDEX ON :CodeChunk(language)",
        "CREATE INDEX ON :CodeChunk(path)",
        "CREATE INDEX ON :CodeChunk(ownerFqn)",
        "CREATE INDEX ON :CodeChunk(signature)");
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
    String cypher = Const.Cypher.CYPHER_WIPE_PROJECT_CODE_BATCH;

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
  @SuppressWarnings("java:S5961")
  void codeChunkRefreshResourcesPreserveEmbeddingOnlyWhenTextHashMatches() {
    String upsert = Const.Cypher.CYPHER_UPSERT_CODE_CHUNKS_BATCH;
    String callsByName = Const.Cypher.CYPHER_UPSERT_CALLS_BY_NAME_BATCH;
    String deleteMissing = Const.Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILES;
    String pathsMissingCodeChunks = Const.Cypher.CYPHER_GET_FILE_PATHS_MISSING_CODE_CHUNKS;
    String wipeCodeRag = Const.Cypher.CYPHER_WIPE_CODE_RAG_BATCH;
    String modelInfo = Const.Cypher.CYPHER_CODE_EMBEDDING_MODEL_INFO;
    String createIndex = Const.Cypher.CYPHER_CREATE_CODE_CHUNK_VECTOR_INDEX;
    String tagCodeVectorLabel = Const.Cypher.CYPHER_TAG_CODE_CHUNK_VECTOR_INDEX_LABEL;
    String showIndex = Const.Cypher.CYPHER_SHOW_VECTOR_INDEX_INFO;
    String countChunks = Const.Cypher.CYPHER_COUNT_CODE_CHUNKS;
    String countDirtyEmbeddings = Const.Cypher.CYPHER_COUNT_DIRTY_CODE_CHUNK_EMBEDDINGS;
    String clearObsoleteEmbeddings = Const.Cypher.CYPHER_CLEAR_OBSOLETE_CODE_CHUNK_EMBEDDINGS;
    String countObsoleteEmbeddings = Const.Cypher.CYPHER_COUNT_OBSOLETE_CODE_CHUNK_EMBEDDINGS;
    String markStaleEmbeddings = Const.Cypher.CYPHER_MARK_STALE_CODE_CHUNK_EMBEDDINGS;
    String refreshEmbeddings = Const.Cypher.CYPHER_REFRESH_CODE_CHUNK_EMBEDDING_BATCH;
    String updateEmbeddingMetadata = Const.Cypher.CYPHER_UPDATE_CODE_CHUNK_EMBEDDING_METADATA;
    String failureDetail = Const.Cypher.CYPHER_GET_CODE_CHUNK_EMBEDDING_FAILURE_DETAIL;
    String createMemoryIndex = Const.Cypher.CYPHER_CREATE_MEMORY_CHUNK_VECTOR_INDEX;
    String tagMemoryVectorLabel = Const.Cypher.CYPHER_TAG_MEMORY_CHUNK_VECTOR_INDEX_LABEL;
    String countMemoryChunks = Const.Cypher.CYPHER_COUNT_MEMORY_CHUNKS;
    String listMemorySources = Const.Cypher.CYPHER_LIST_MEMORY_CHUNK_SOURCES;
    String deleteStaleMemoryChunks = Const.Cypher.CYPHER_DELETE_STALE_MEMORY_CHUNKS;
    String wipeMemoryRag = Const.Cypher.CYPHER_WIPE_MEMORY_RAG_BATCH;
    String upsertMemoryChunks = Const.Cypher.CYPHER_UPSERT_MEMORY_CHUNKS_BATCH;
    String clearObsoleteMemoryEmbeddings =
        Const.Cypher.CYPHER_CLEAR_OBSOLETE_MEMORY_CHUNK_EMBEDDINGS;
    String countObsoleteMemoryEmbeddings =
        Const.Cypher.CYPHER_COUNT_OBSOLETE_MEMORY_CHUNK_EMBEDDINGS;
    String markStaleMemoryEmbeddings = Const.Cypher.CYPHER_MARK_STALE_MEMORY_CHUNK_EMBEDDINGS;
    String refreshMemoryEmbeddings = Const.Cypher.CYPHER_REFRESH_MEMORY_CHUNK_EMBEDDING_BATCH;
    String updateMemoryEmbeddingMetadata =
        Const.Cypher.CYPHER_UPDATE_MEMORY_CHUNK_EMBEDDING_METADATA;
    String memoryFailureDetail = Const.Cypher.CYPHER_GET_MEMORY_CHUNK_EMBEDDING_FAILURE_DETAIL;

    assertContainsAll(
        upsert,
        "MERGE (chunk:CodeChunk",
        "chunk.textHash AS previousTextHash",
        "previousTextHash <> row.textHash",
        "REMOVE chunk.embedding",
        "SET chunk.embeddingDirty = true",
        "previousTextHash = row.textHash",
        "SET chunk.embeddingDirty = false",
        "MATCH (source:File",
        "MATCH (source:Class",
        "MATCH (source:Interface",
        "MATCH (source:Annotation",
        "MATCH (source:Method",
        "MATCH (source:Field",
        "MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)");
    assertFalse(upsert.contains("DELETE rel"));
    assertContainsAll(
        callsByName,
        "UNWIND $rows AS row",
        "row.caller AS callerSignature",
        "[:EXTENDS*1..]",
        "WHERE size(directCandidates) = 0",
        "WHERE size(directCandidates) = 0 AND size(classCandidates) = 0",
        "MERGE (caller)-[call:CALLS]->(callee)",
        "SET call.count = coalesce(call.count, 0) + callCount");
    assertFalse(callsByName.contains("[:EXTENDS*1..16]"));
    assertFalse(callsByName.contains("[:EXTENDS*0..16]"));
    assertFalse(Const.Cypher.CYPHER_RESOLVE_PENDING_CALLS.contains("[:EXTENDS*1..16]"));
    assertFalse(Const.Cypher.CYPHER_RESOLVE_PENDING_CALLS.contains("[:EXTENDS*0..16]"));
    assertFalse(Const.Cypher.CYPHER_RESOLVE_PENDING_CALLS_SCOPED.contains("[:EXTENDS*1..16]"));
    assertFalse(Const.Cypher.CYPHER_RESOLVE_PENDING_CALLS_SCOPED.contains("[:EXTENDS*0..16]"));
    assertContainsAll(
        Const.Cypher.CYPHER_UPSERT_PENDING_CALLS_BY_NAME_BATCH,
        "row.allowNameOnly",
        "pending.allowNameOnly");
    assertContainsAll(
        Const.Cypher.CYPHER_RESOLVE_PENDING_CALLS, "coalesce(pending.allowNameOnly, false) = true");
    assertContainsAll(
        Const.Cypher.CYPHER_RESOLVE_PENDING_CALLS_SCOPED,
        "coalesce(pending.allowNameOnly, false) = true");

    assertTrue(Const.Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILE.contains("chunk:CodeChunk"));
    assertContainsAll(
        deleteMissing, "chunk.path STARTS WITH $sourceRootPrefix", "NOT chunk.path IN $paths");
    assertContainsAll(
        pathsMissingCodeChunks, "OPTIONAL MATCH (chunk:CodeChunk", "WHERE chunkCount = 0");
    assertTrue(Const.Cypher.CYPHER_DELETE_CODE_CHUNKS_FOR_FILE_EXCEPT.contains("chunk.id IN $ids"));
    assertContainsAll(
        wipeCodeRag,
        "MATCH (chunk:CodeChunk {project: $project})",
        "DETACH DELETE chunk",
        "RETURN count(chunk) AS deleted");
    assertTrue(modelInfo.contains("CALL embeddings.model_info($config)"));
    assertContainsAll(
        createIndex, "CREATE VECTOR INDEX __INDEX_NAME__", "ON :__VECTOR_INDEX_LABEL__");
    assertContainsAll(
        tagCodeVectorLabel,
        "MATCH (chunk:CodeChunk {project: $project})",
        "SET chunk:__VECTOR_INDEX_LABEL__",
        "RETURN count(chunk) AS count");
    assertTrue(showIndex.contains("SHOW VECTOR INDEX INFO"));
    assertTrue(countChunks.contains("RETURN count(chunk) AS count"));
    assertTrue(countChunks.contains("{project: $project}"));
    assertContainsAll(countDirtyEmbeddings, "embeddingDirty: true", "RETURN count(chunk) AS count");
    assertContainsAll(
        clearObsoleteEmbeddings,
        "MATCH (chunk:CodeChunk {project: $project})",
        "REMOVE chunk.embedding",
        "chunk.embeddingModel <> $modelName",
        "chunk.embeddingDimensions <> $dimension");
    assertContainsAll(
        countObsoleteEmbeddings,
        "MATCH (chunk:CodeChunk {project: $project})",
        "RETURN count(chunk) AS count");
    assertContainsAll(
        markStaleEmbeddings,
        "RETURN count(chunk) AS count",
        "chunk.embeddingModel <> $modelName",
        "coalesce(chunk.embeddingDirty, false) = false",
        "SET chunk.embeddingDirty = true");
    assertContainsAll(
        refreshEmbeddings,
        "CALL embeddings.node_sentence(chunks, $config)",
        "embeddingDirty: true",
        "RETURN success AS success");
    assertFalse(refreshEmbeddings.contains("ORDER BY"));
    // Memgraph permits only RETURN after the writeable node_sentence CALL; metadata stamping
    // must stay a separate statement.
    assertEndsWithReturnAfterNodeSentence(refreshEmbeddings);
    assertContainsAll(
        updateEmbeddingMetadata,
        "SET chunk.embeddingModel = $modelName",
        "chunk.embeddingDirty = false");
    assertTrue(failureDetail.contains("substring(chunk.text, 0, 240) AS preview"));
    assertContainsAll(
        createMemoryIndex, "CREATE VECTOR INDEX __INDEX_NAME__", "ON :__VECTOR_INDEX_LABEL__");
    assertContainsAll(
        tagMemoryVectorLabel,
        "MATCH (chunk:MemoryChunk {project: $project})",
        "SET chunk:__VECTOR_INDEX_LABEL__",
        "RETURN count(chunk) AS count");
    assertTrue(countMemoryChunks.contains("MATCH (chunk:MemoryChunk"));
    assertTrue(countMemoryChunks.contains("{project: $project}"));
    assertContainsAll(listMemorySources, "MATCH (root:Memory", "HAS_RAG_CHUNK");
    assertFalse(listMemorySources.contains("ORDER BY sourceLabel, sourceId"));
    assertContainsAll(
        deleteStaleMemoryChunks,
        "DETACH DELETE chunk",
        "MATCH (chunk:MemoryChunk {project: $project})",
        "HAS_RAG_CHUNK");
    assertFalse(deleteStaleMemoryChunks.contains("$rows"));
    assertContainsAll(
        wipeMemoryRag,
        "MATCH (chunk:MemoryChunk {project: $project})",
        "DETACH DELETE chunk",
        "RETURN count(chunk) AS deleted");
    assertContainsAll(
        upsertMemoryChunks,
        "MERGE (chunk:MemoryChunk",
        "previousTextHash <> row.textHash",
        "REMOVE chunk.embedding",
        "SET chunk.embeddingDirty = true",
        "MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)");
    assertContainsAll(
        clearObsoleteMemoryEmbeddings,
        "MATCH (chunk:MemoryChunk {project: $project})",
        "REMOVE chunk.embedding",
        "chunk.embeddingModel <> $modelName");
    assertTrue(
        countObsoleteMemoryEmbeddings.contains("MATCH (chunk:MemoryChunk {project: $project})"));
    assertContainsAll(
        markStaleMemoryEmbeddings,
        "chunk.embeddingModel <> $modelName",
        "coalesce(chunk.embeddingDirty, false) = false",
        "SET chunk.embeddingDirty = true");
    assertContainsAll(
        refreshMemoryEmbeddings,
        "CALL embeddings.node_sentence(chunks, $config)",
        "embeddingDirty: true");
    assertFalse(refreshMemoryEmbeddings.contains("ORDER BY"));
    assertEndsWithReturnAfterNodeSentence(refreshMemoryEmbeddings);
    assertContainsAll(
        updateMemoryEmbeddingMetadata,
        "SET chunk.embeddingModel = $modelName",
        "chunk.embeddingDirty = false");
    assertTrue(memoryFailureDetail.contains("substring(chunk.text, 0, 240) AS preview"));
  }

  @Test
  void deleteStaleDefinitionsResourceConsolidatesDefinitionCleanup() {
    String cypher = Const.Cypher.CYPHER_DELETE_STALE_DEFINITIONS_FOR_FILE;

    assertEquals(
        1,
        occurrences(cypher, "OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(node)"));
    assertTrue(cypher.contains("collect(DISTINCT defines) AS staleDefines"));
    assertTrue(cypher.contains("collect(DISTINCT node) AS candidates"));
    assertFalse(cypher.contains("staleCurrentOwnerMembersDeleted"));
    assertFalse(cypher.contains("staleOwnerMembersDeleted"));
    assertFalse(cypher.contains("staleOwnersDeleted"));
    assertFalse(cypher.contains("staleMethodsDeleted"));
    assertFalse(cypher.contains("staleFieldsDeleted"));
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
