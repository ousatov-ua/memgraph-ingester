UNWIND $rows AS row
MATCH (chunk:CodeChunk {id: row.id, project: $project})
MATCH (source:Class {fqn: row.sourceId, project: $project})
MERGE (source)-[:HAS_RAG_CHUNK]->(chunk)
