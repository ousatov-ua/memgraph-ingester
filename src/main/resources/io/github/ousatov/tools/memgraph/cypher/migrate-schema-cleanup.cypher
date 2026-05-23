MATCH (code:Code)-[rel:CONTAINS]->(file:File)
WHERE code.language IS NOT NULL
  AND file.language IS NOT NULL
  AND code.language <> file.language
DELETE rel;

MATCH (code:Code)-[rel:CONTAINS]->(pkg:Package)
WHERE code.language IS NOT NULL
  AND pkg.language IS NOT NULL
  AND code.language <> pkg.language
DELETE rel;

MATCH (project:Project)-[rel:CONTAINS]->(code:Code)
WHERE code.project = project.name
DELETE rel;

MATCH (legacy:Code)
WHERE legacy.language IS NULL
DETACH DELETE legacy;
