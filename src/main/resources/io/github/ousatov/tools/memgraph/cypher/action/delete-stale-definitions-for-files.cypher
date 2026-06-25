UNWIND $rows AS row
WITH row, row.path AS path
MATCH (sourceFile:File {path: path, project: $project})
MATCH (sourceFile)-[defines:DEFINES]->(node)
WHERE node.project = $project
  AND (node:Class OR node:Interface OR node:Annotation OR node:Method OR node:Field)
WITH sourceFile,
    defines,
    row,
    node,
    (
      (node:Class AND NOT node.fqn IN row.classFqns)
      OR (node:Interface AND NOT node.fqn IN row.interfaceFqns)
      OR (node:Annotation AND NOT node.fqn IN row.annotationFqns)
      OR (node:Method AND NOT node.signature IN row.methodSignatures)
      OR (node:Field AND NOT node.fqn IN row.fieldFqns)
    ) AS staleDefinition,
    (
      (node:Class AND NOT node.fqn IN row.classFqns)
      OR (node:Interface AND NOT node.fqn IN row.interfaceFqns)
      OR (node:Annotation AND NOT node.fqn IN row.annotationFqns)
    ) AS staleOwner
OPTIONAL MATCH (node)-[:DECLARES]->(member)<-[memberDefines:DEFINES]-(sourceFile)
WHERE staleOwner
  AND member.project = $project
  AND (member:Method OR member:Field)
WITH collect(DISTINCT CASE WHEN staleDefinition THEN defines ELSE null END) AS directDefines,
    collect(DISTINCT CASE WHEN staleDefinition THEN node ELSE null END) AS directNodes,
    collect(DISTINCT memberDefines) AS memberDefines,
    collect(DISTINCT member) AS memberNodes
WITH [rel IN directDefines + memberDefines WHERE rel IS NOT NULL] AS staleDefines,
    [candidate IN directNodes + memberNodes WHERE candidate IS NOT NULL] AS candidates
FOREACH (defines IN staleDefines | DELETE defines)
WITH candidates
UNWIND candidates AS node
OPTIONAL MATCH (other:File {project: $project})-[:DEFINES]->(node)
WITH node,
    count(other)
    + size(
      [batchRow IN $rows
      WHERE (node:Class AND node.fqn IN batchRow.classFqns)
        OR (node:Interface AND node.fqn IN batchRow.interfaceFqns)
        OR (node:Annotation AND node.fqn IN batchRow.annotationFqns)
        OR (node:Method AND node.signature IN batchRow.methodSignatures)
        OR (node:Field AND node.fqn IN batchRow.fieldFqns)]
    ) AS remainingDefinitions
WHERE remainingDefinitions = 0
WITH collect(DISTINCT node) AS staleDefinitions
FOREACH (node IN staleDefinitions | DETACH DELETE node)
RETURN size(staleDefinitions) AS deleted
