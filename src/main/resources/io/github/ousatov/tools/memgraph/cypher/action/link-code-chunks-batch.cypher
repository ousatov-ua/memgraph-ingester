UNWIND $rows AS row
MATCH (chunk:CodeChunk {id: row.id, project: $project})
OPTIONAL MATCH (file:File {path: row.sourceId, project: $project})
WITH row, chunk, file
OPTIONAL MATCH (classNode:Class {fqn: row.sourceId, project: $project})
WITH row, chunk, file, classNode
OPTIONAL MATCH (interfaceNode:Interface {fqn: row.sourceId, project: $project})
WITH row, chunk, file, classNode, interfaceNode
OPTIONAL MATCH (annotationNode:Annotation {fqn: row.sourceId, project: $project})
WITH row, chunk, file, classNode, interfaceNode, annotationNode
OPTIONAL MATCH (methodNode:Method {signature: row.sourceId, project: $project})
WITH row, chunk, file, classNode, interfaceNode, annotationNode, methodNode
OPTIONAL MATCH (fieldNode:Field {fqn: row.sourceId, project: $project})
WITH row, chunk, file, classNode, interfaceNode, annotationNode, methodNode, fieldNode
FOREACH (_ IN CASE WHEN row.sourceLabel = 'File' AND file IS NOT NULL THEN [1] ELSE [] END |
  MERGE (file)-[:HAS_RAG_CHUNK]->(chunk)
)
FOREACH (_ IN CASE WHEN row.sourceLabel = 'Class' AND classNode IS NOT NULL THEN [1] ELSE [] END |
  MERGE (classNode)-[:HAS_RAG_CHUNK]->(chunk)
)
FOREACH (_ IN CASE WHEN row.sourceLabel = 'Interface' AND interfaceNode IS NOT NULL THEN [1] ELSE [] END |
  MERGE (interfaceNode)-[:HAS_RAG_CHUNK]->(chunk)
)
FOREACH (_ IN CASE WHEN row.sourceLabel = 'Annotation' AND annotationNode IS NOT NULL THEN [1] ELSE [] END |
  MERGE (annotationNode)-[:HAS_RAG_CHUNK]->(chunk)
)
FOREACH (_ IN CASE WHEN row.sourceLabel = 'Method' AND methodNode IS NOT NULL THEN [1] ELSE [] END |
  MERGE (methodNode)-[:HAS_RAG_CHUNK]->(chunk)
)
FOREACH (_ IN CASE WHEN row.sourceLabel = 'Field' AND fieldNode IS NOT NULL THEN [1] ELSE [] END |
  MERGE (fieldNode)-[:HAS_RAG_CHUNK]->(chunk)
)
