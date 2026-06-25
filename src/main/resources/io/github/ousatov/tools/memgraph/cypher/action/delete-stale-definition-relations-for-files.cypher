UNWIND $rows AS row
WITH row, row.path AS path
MATCH (sourceFile:File {path: path, project: $project})
CALL {
  WITH sourceFile, row
  MATCH (sourceFile)-[:DEFINES]->(node)
  WHERE node.project = $project
    AND (node:Class OR node:Interface OR node:Annotation OR node:Method OR node:Field)
  RETURN node, true AS definedBySourceFile
  UNION
  WITH row
  MATCH (node:Method {project: $project})
  WHERE node.signature IN row.methodSignatures
  RETURN node, false AS definedBySourceFile
  UNION
  WITH row
  MATCH (node:Field {project: $project})
  WHERE node.fqn IN row.fieldFqns
  RETURN node, false AS definedBySourceFile
  UNION
  WITH row
  MATCH (node {project: $project})
  WHERE (node:Class AND node.fqn IN row.classFqns)
    OR (node:Interface AND node.fqn IN row.interfaceFqns)
    OR (node:Annotation AND node.fqn IN row.annotationFqns)
  RETURN node, false AS definedBySourceFile
}
WITH path, node, max(CASE WHEN definedBySourceFile THEN 1 ELSE 0 END) AS sourceDefinitions
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(node)
WHERE retainedFile.path <> path
  AND ($retainedSourceToken = '' OR retainedFile.retainedSourceToken = $retainedSourceToken)
WITH node, sourceDefinitions, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
WITH collect(DISTINCT node) AS staleRelationNodes,
    collect(DISTINCT CASE WHEN sourceDefinitions > 0 THEN node ELSE null END)
        AS staleSourceRelationNodes
CALL {
  WITH staleRelationNodes
  UNWIND staleRelationNodes AS node
  OPTIONAL MATCH (node)-[r]->(target)
  WHERE (node:Method AND type(r) = 'CALLS' AND target:Method AND target.project = $project)
    OR (
      (node:Class OR node:Interface OR node:Annotation OR node:Method OR node:Field)
      AND type(r) = 'ANNOTATED_WITH'
      AND target:Annotation
      AND target.project = $project
    )
    OR (
      (node:Class OR node:Interface OR node:Annotation)
      AND type(r) IN ['EXTENDS', 'IMPLEMENTS']
    )
  RETURN collect(DISTINCT r) AS relations
}
WITH collect(relations) AS relationGroups, staleSourceRelationNodes
FOREACH (relationGroup IN relationGroups |
  FOREACH (relation IN relationGroup | DELETE relation)
)
WITH staleSourceRelationNodes
UNWIND staleSourceRelationNodes AS node
OPTIONAL MATCH (node)-[:PENDING_CALL]->(pending:PendingCall {project: $project})
WHERE node:Method
WITH collect(DISTINCT pending) AS pendingCalls
FOREACH (pending IN pendingCalls | DETACH DELETE pending)
RETURN count(*) AS files
