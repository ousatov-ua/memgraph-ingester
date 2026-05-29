CALL {
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
    AND (size($paths) = 0 OR retainedFile.path IN $paths)
  WITH node, sourceDefinitions, count(retainedFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT node) AS staleRelationNodes,
      collect(DISTINCT CASE WHEN sourceDefinitions > 0 THEN node ELSE null END)
          AS staleSourceRelationNodes
  CALL {
    WITH staleRelationNodes
    UNWIND staleRelationNodes AS node
    OPTIONAL MATCH (node)-[r:CALLS]->(:Method {project: $project})
    WHERE node:Method
    RETURN collect(DISTINCT r) AS relations
    UNION
    WITH staleRelationNodes
    UNWIND staleRelationNodes AS node
    OPTIONAL MATCH (node)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
    WHERE node:Class OR node:Interface OR node:Annotation OR node:Method OR node:Field
    RETURN collect(DISTINCT r) AS relations
    UNION
    WITH staleRelationNodes
    UNWIND staleRelationNodes AS node
    OPTIONAL MATCH (node)-[r]->()
    WHERE (node:Class OR node:Interface OR node:Annotation)
      AND type(r) IN ['EXTENDS', 'IMPLEMENTS']
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
  RETURN relationshipsDeleted + size(pendingCalls) AS relationshipsDeleted
}
CALL {
  MATCH (:File {path: $path, project: $project})-[defines:DEFINES]->(member)
  MATCH (owner)-[:DECLARES]->(member)
  WHERE owner.project = $project
    AND (
      (owner:Class AND owner.fqn IN $classFqns)
      OR (owner:Interface AND owner.fqn IN $interfaceFqns)
      OR (owner:Annotation AND owner.fqn IN $annotationFqns)
    )
    AND member.project = $project
    AND (
      (member:Method AND NOT member.signature IN $methodSignatures)
      OR (member:Field AND NOT member.fqn IN $fieldFqns)
    )
  WITH collect(DISTINCT defines) AS staleDefines, collect(DISTINCT member) AS candidates
  FOREACH (defines IN staleDefines | DELETE defines)
  WITH candidates
  UNWIND candidates AS member
  OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(member)
  WITH member, count(other) AS remainingDefinitions
  WHERE remainingDefinitions = 0
  WITH collect(DISTINCT member) AS staleMembers
  FOREACH (member IN staleMembers | DETACH DELETE member)
  RETURN size(staleMembers) AS staleCurrentOwnerMembersDeleted
}
CALL {
  MATCH (sourceFile:File {path: $path, project: $project})-[:DEFINES]->(owner)-[:DECLARES]->(member)
  MATCH (sourceFile)-[defines:DEFINES]->(member)
  WHERE owner.project = $project
    AND (
      (owner:Class AND NOT owner.fqn IN $classFqns)
      OR (owner:Interface AND NOT owner.fqn IN $interfaceFqns)
      OR (owner:Annotation AND NOT owner.fqn IN $annotationFqns)
    )
    AND member.project = $project
    AND (member:Method OR member:Field)
  WITH collect(DISTINCT defines) AS staleDefines, collect(DISTINCT member) AS candidates
  FOREACH (defines IN staleDefines | DELETE defines)
  WITH candidates
  UNWIND candidates AS member
  OPTIONAL MATCH (otherFile:File {project: $project})-[:DEFINES]->(member)
  WITH member, count(otherFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT member) AS staleMembers
  FOREACH (member IN staleMembers | DETACH DELETE member)
  RETURN size(staleMembers) AS staleOwnerMembersDeleted
}
CALL {
  MATCH (:File {path: $path, project: $project})-[defines:DEFINES]->(owner)
  WHERE owner.project = $project
    AND (
      (owner:Class AND NOT owner.fqn IN $classFqns)
      OR (owner:Interface AND NOT owner.fqn IN $interfaceFqns)
      OR (owner:Annotation AND NOT owner.fqn IN $annotationFqns)
    )
  WITH collect(DISTINCT defines) AS staleDefines, collect(DISTINCT owner) AS candidates
  FOREACH (defines IN staleDefines | DELETE defines)
  WITH candidates
  UNWIND candidates AS owner
  OPTIONAL MATCH (definingFile:File {project: $project})-[:DEFINES]->(owner)
  WITH owner, count(definingFile) AS remainingDefinitions
  WHERE remainingDefinitions = 0
  WITH collect(DISTINCT owner) AS staleOwners
  FOREACH (owner IN staleOwners | DETACH DELETE owner)
  RETURN size(staleOwners) AS staleOwnersDeleted
}
CALL {
  MATCH (sourceFile:File {path: $path, project: $project})-[defines:DEFINES]->(method:Method {project: $project})
  WHERE NOT method.signature IN $methodSignatures
  WITH collect(DISTINCT defines) AS staleDefines, collect(DISTINCT method) AS candidates
  FOREACH (defines IN staleDefines | DELETE defines)
  WITH candidates
  UNWIND candidates AS method
  OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(method)
  WITH method, count(other) AS remainingDefinitions
  WHERE remainingDefinitions = 0
  WITH collect(DISTINCT method) AS staleMethods
  FOREACH (method IN staleMethods | DETACH DELETE method)
  RETURN size(staleMethods) AS staleMethodsDeleted
}
CALL {
  MATCH (sourceFile:File {path: $path, project: $project})-[defines:DEFINES]->(field:Field {project: $project})
  WHERE NOT field.fqn IN $fieldFqns
  WITH collect(DISTINCT defines) AS staleDefines, collect(DISTINCT field) AS candidates
  FOREACH (defines IN staleDefines | DELETE defines)
  WITH candidates
  UNWIND candidates AS field
  OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(field)
  WITH field, count(other) AS remainingDefinitions
  WHERE remainingDefinitions = 0
  WITH collect(DISTINCT field) AS staleFields
  FOREACH (field IN staleFields | DETACH DELETE field)
  RETURN size(staleFields) AS staleFieldsDeleted
}
RETURN relationshipsDeleted
  + staleCurrentOwnerMembersDeleted
  + staleOwnerMembersDeleted
  + staleOwnersDeleted
  + staleMethodsDeleted
  + staleFieldsDeleted AS deleted
