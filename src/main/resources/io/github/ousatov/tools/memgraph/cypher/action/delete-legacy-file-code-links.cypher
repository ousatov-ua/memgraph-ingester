MATCH (oldCode:Code {project: $project})-[oldRel:CONTAINS]->(file:File {project: $project})
WHERE oldCode.language IS NULL OR oldCode.language <> file.language
DELETE oldRel
