CALL {
  MATCH (file:File {path: $path, project: $project})-[:DEFINES]->(caller:Method {project: $project})-[:PENDING_CALL]->(pending:PendingCall {project: $project})
  OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(caller)
  WHERE retainedFile.path <> file.path
    AND (size($paths) = 0 OR retainedFile.path IN $paths)
  WITH pending, count(retainedFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT pending) AS pendingCalls
  FOREACH (pending IN pendingCalls | DETACH DELETE pending)
  RETURN size(pendingCalls) AS pendingCallsDeleted
}
CALL {
  MATCH (caller:Method {project: $project})-[r:CALLS]->(:Method {project: $project})
  WHERE caller.signature IN $methodSignatures
  OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(caller)
  WHERE retainedFile.path <> $path
    AND (size($paths) = 0 OR retainedFile.path IN $paths)
  WITH r, count(retainedFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT r) AS calls
  FOREACH (r IN calls | DELETE r)
  RETURN size(calls) AS currentOwnerCallsDeleted
}
CALL {
  MATCH (owner)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
  WHERE owner.project = $project
    AND (
      (owner:Class AND owner.fqn IN $classFqns)
      OR (owner:Interface AND owner.fqn IN $interfaceFqns)
      OR (owner:Annotation AND owner.fqn IN $annotationFqns)
    )
  OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(owner)
  WHERE retainedFile.path <> $path
    AND (size($paths) = 0 OR retainedFile.path IN $paths)
  WITH r, count(retainedFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT r) AS annotations
  FOREACH (r IN annotations | DELETE r)
  RETURN size(annotations) AS currentOwnerAnnotationsDeleted
}
CALL {
  MATCH (member)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
  WHERE member.project = $project
    AND (
      (member:Method AND member.signature IN $methodSignatures)
      OR (member:Field AND member.fqn IN $fieldFqns)
    )
  OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(member)
  WHERE retainedFile.path <> $path
    AND (size($paths) = 0 OR retainedFile.path IN $paths)
  WITH r, count(retainedFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT r) AS annotations
  FOREACH (r IN annotations | DELETE r)
  RETURN size(annotations) AS currentMemberAnnotationsDeleted
}
CALL {
  MATCH (owner)-[r]->()
  WHERE owner.project = $project
    AND (
      (owner:Class AND owner.fqn IN $classFqns)
      OR (owner:Interface AND owner.fqn IN $interfaceFqns)
      OR (owner:Annotation AND owner.fqn IN $annotationFqns)
    )
    AND type(r) IN ['EXTENDS', 'IMPLEMENTS']
  OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(owner)
  WHERE retainedFile.path <> $path
    AND (size($paths) = 0 OR retainedFile.path IN $paths)
  WITH r, count(retainedFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT r) AS relations
  FOREACH (r IN relations | DELETE r)
  RETURN size(relations) AS currentTypeRelationsDeleted
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
  MATCH (:File {path: $path, project: $project})-[:DEFINES]->(caller:Method {project: $project})-[r:CALLS]->(:Method {project: $project})
  OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(caller)
  WHERE retainedFile.path <> $path
    AND (size($paths) = 0 OR retainedFile.path IN $paths)
  WITH r, count(retainedFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT r) AS calls
  FOREACH (r IN calls | DELETE r)
  RETURN size(calls) AS callsDeleted
}
CALL {
  MATCH (file:File {path: $path, project: $project})-[:DEFINES]->(owner)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
  WHERE owner.project = $project
    AND (owner:Class OR owner:Interface OR owner:Annotation)
  OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(owner)
  WHERE retainedFile.path <> file.path
    AND (size($paths) = 0 OR retainedFile.path IN $paths)
  WITH r, count(retainedFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT r) AS annotations
  FOREACH (r IN annotations | DELETE r)
  RETURN size(annotations) AS ownerAnnotationsDeleted
}
CALL {
  MATCH (:File {path: $path, project: $project})-[:DEFINES]->(member)-[r:ANNOTATED_WITH]->(:Annotation {project: $project})
  WHERE member.project = $project
    AND (member:Method OR member:Field)
  OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(member)
  WHERE retainedFile.path <> $path
    AND (size($paths) = 0 OR retainedFile.path IN $paths)
  WITH r, count(retainedFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT r) AS annotations
  FOREACH (r IN annotations | DELETE r)
  RETURN size(annotations) AS memberAnnotationsDeleted
}
CALL {
  MATCH (file:File {path: $path, project: $project})-[:DEFINES]->(owner)-[r]->()
  WHERE owner.project = $project
    AND (owner:Class OR owner:Interface OR owner:Annotation)
    AND type(r) IN ['EXTENDS', 'IMPLEMENTS']
  OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(owner)
  WHERE retainedFile.path <> file.path
    AND (size($paths) = 0 OR retainedFile.path IN $paths)
  WITH r, count(retainedFile) AS retainedDefinitions
  WHERE retainedDefinitions = 0
  WITH collect(DISTINCT r) AS relations
  FOREACH (r IN relations | DELETE r)
  RETURN size(relations) AS typeRelationsDeleted
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
RETURN pendingCallsDeleted
  + currentOwnerCallsDeleted
  + currentOwnerAnnotationsDeleted
  + currentMemberAnnotationsDeleted
  + currentTypeRelationsDeleted
  + staleCurrentOwnerMembersDeleted
  + staleOwnerMembersDeleted
  + staleOwnersDeleted
  + callsDeleted
  + ownerAnnotationsDeleted
  + memberAnnotationsDeleted
  + typeRelationsDeleted
  + staleMethodsDeleted
  + staleFieldsDeleted AS deleted
