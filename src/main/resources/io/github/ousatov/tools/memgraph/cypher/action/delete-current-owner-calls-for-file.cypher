MATCH (caller:Method {project: $project})-[r:CALLS]->(:Method {project: $project})
WHERE caller.signature IN $methodSignatures
OPTIONAL MATCH (retainedFile:File {project: $project})-[:DEFINES]->(caller)
WHERE retainedFile.path <> $path
  AND (size($paths) = 0 OR retainedFile.path IN $paths)
WITH r, count(retainedFile) AS retainedDefinitions
WHERE retainedDefinitions = 0
DELETE r
