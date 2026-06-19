MATCH (sourceFile:File {path: $path, project: $project})
CALL {
  WITH sourceFile
  MATCH (sourceFile)-[:DEFINES]->(node)
  WHERE node.project = $project
    AND (node:Class OR node:Interface OR node:Annotation OR node:Method OR node:Field)
  RETURN node, true AS definedBySourceFile
  UNION
  MATCH (node:Method {project: $project})
  WHERE node.signature IN $methodSignatures
  RETURN node, false AS definedBySourceFile
  UNION
  MATCH (node:Field {project: $project})
  WHERE node.fqn IN $fieldFqns
  RETURN node, false AS definedBySourceFile
  UNION
  MATCH (node {project: $project})
  WHERE (node:Class AND node.fqn IN $classFqns)
    OR (node:Interface AND node.fqn IN $interfaceFqns)
    OR (node:Annotation AND node.fqn IN $annotationFqns)
  RETURN node, false AS definedBySourceFile
}
WITH node, max(CASE WHEN definedBySourceFile THEN 1 ELSE 0 END) AS sourceDefinitions
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(node)
WHERE retainedFile.path <> $path
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
WITH reduce(total = 0, relationGroup IN relationGroups | total + size(relationGroup))
    AS relationshipsDeleted, staleSourceRelationNodes
UNWIND staleSourceRelationNodes AS node
OPTIONAL MATCH (node)-[:PENDING_CALL]->(pending:PendingCall {project: $project})
WHERE node:Method
WITH relationshipsDeleted, collect(DISTINCT pending) AS pendingCalls
FOREACH (pending IN pendingCalls | DETACH DELETE pending)
RETURN relationshipsDeleted + size(pendingCalls) AS deleted
