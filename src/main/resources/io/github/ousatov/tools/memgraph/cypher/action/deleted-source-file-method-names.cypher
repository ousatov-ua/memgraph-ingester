MATCH (sourceFile:File {path: $path, project: $project})
MATCH (sourceFile)-[:DEFINES]->(node)
WHERE node.project = $project
  AND (node:Class OR node:Interface OR node:Annotation OR node:Method OR node:Field)
WITH sourceFile,
    node,
    (
      (node:Class AND NOT node.fqn IN $classFqns)
      OR (node:Interface AND NOT node.fqn IN $interfaceFqns)
      OR (node:Annotation AND NOT node.fqn IN $annotationFqns)
      OR (node:Method AND NOT node.signature IN $methodSignatures)
      OR (node:Field AND NOT node.fqn IN $fieldFqns)
    ) AS staleDefinition,
    (
      (node:Class AND NOT node.fqn IN $classFqns)
      OR (node:Interface AND NOT node.fqn IN $interfaceFqns)
      OR (node:Annotation AND NOT node.fqn IN $annotationFqns)
    ) AS staleOwner
OPTIONAL MATCH (node)-[:DECLARES]->(member)<-[:DEFINES]-(sourceFile)
WHERE staleOwner
  AND member.project = $project
  AND (member:Method OR member:Field)
WITH sourceFile,
    collect(DISTINCT CASE WHEN staleDefinition THEN node ELSE null END) AS directNodes,
    collect(DISTINCT member) AS memberNodes
WITH sourceFile,
    [candidate IN directNodes + memberNodes WHERE candidate IS NOT NULL AND candidate:Method]
        AS candidates
UNWIND candidates AS method
OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(method)
WHERE other <> sourceFile
WITH method, count(other) AS retainedDefinitions
WHERE retainedDefinitions = 0
RETURN DISTINCT method.name AS name
