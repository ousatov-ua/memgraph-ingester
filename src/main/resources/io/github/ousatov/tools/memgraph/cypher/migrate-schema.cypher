DROP CONSTRAINT ON (c:Code) ASSERT c.project IS UNIQUE;
DROP CONSTRAINT ON (p:Package) ASSERT p.name, p.project IS UNIQUE;

MATCH (legacy:Code)
WHERE legacy.language IS NULL
OPTIONAL MATCH (existing:Code {project: legacy.project, language: 'java'})
WITH legacy, existing
WHERE existing IS NULL
SET legacy.language = 'java',
    legacy.languageName = 'Java',
    legacy.lastIngested = coalesce(legacy.lastIngested, timestamp());

MATCH (code:Code)
WHERE code.project IS NOT NULL
  AND code.language IN ['java', 'javascript']
WITH code,
     CASE code.language
       WHEN 'javascript' THEN 'Js'
       ELSE 'Java'
     END AS languageName
MERGE (project:Project {name: code.project})
MERGE (language:Language {project: code.project, name: languageName})
  SET language.graphName = code.language
SET code.languageName = languageName,
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code);

MATCH (file:File)
WHERE file.project IS NOT NULL
WITH file,
     CASE file.language
       WHEN 'javascript' THEN 'javascript'
       ELSE 'java'
     END AS graphName,
     CASE file.language
       WHEN 'javascript' THEN 'Js'
       ELSE 'Java'
     END AS languageName
MERGE (project:Project {name: file.project})
MERGE (language:Language {project: file.project, name: languageName})
  SET language.graphName = graphName
MERGE (code:Code {project: file.project, language: graphName})
SET code.languageName = languageName,
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code)
MERGE (code)-[:CONTAINS]->(file)
SET file.language = graphName;

MATCH (pkg:Package)
WHERE pkg.project IS NOT NULL
OPTIONAL MATCH (pkg)-[:CONTAINS]->(declared)
WITH pkg,
     sum(CASE WHEN declared.language = 'javascript' THEN 1 ELSE 0 END) AS jsDeclarations
WITH pkg,
     CASE
       WHEN pkg.language = 'javascript' THEN 'javascript'
       WHEN pkg.language = 'java' THEN 'java'
       WHEN jsDeclarations > 0 THEN 'javascript'
       WHEN pkg.name = 'js' THEN 'javascript'
       WHEN pkg.name STARTS WITH 'js.' THEN 'javascript'
       ELSE 'java'
     END AS graphName
WITH pkg,
     graphName,
     CASE graphName
       WHEN 'javascript' THEN 'Js'
       ELSE 'Java'
     END AS languageName
MERGE (project:Project {name: pkg.project})
MERGE (language:Language {project: pkg.project, name: languageName})
  SET language.graphName = graphName
MERGE (code:Code {project: pkg.project, language: graphName})
SET code.languageName = languageName,
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code)
MERGE (code)-[:CONTAINS]->(pkg)
SET pkg.language = graphName;

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
