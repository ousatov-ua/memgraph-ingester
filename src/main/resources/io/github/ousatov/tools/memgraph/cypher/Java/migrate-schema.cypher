MATCH (legacy:Code)
WHERE legacy.language IS NULL
OPTIONAL MATCH (existing:Code {project: legacy.project, language: 'java'})
WITH legacy, existing
WHERE existing IS NULL
SET legacy.language = 'java',
    legacy.languageName = 'Java',
    legacy.lastIngested = coalesce(legacy.lastIngested, timestamp());

MATCH (code:Code {language: 'java'})
WHERE code.project IS NOT NULL
MERGE (project:Project {name: code.project})
MERGE (language:Language {project: code.project, name: 'Java'})
  SET language.graphName = 'java'
SET code.languageName = 'Java',
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code);

MATCH (file:File)
WHERE file.project IS NOT NULL
  AND (file.language = 'java' OR file.language IS NULL)
MERGE (project:Project {name: file.project})
MERGE (language:Language {project: file.project, name: 'Java'})
  SET language.graphName = 'java'
MERGE (code:Code {project: file.project, language: 'java'})
SET code.languageName = 'Java',
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code)
MERGE (code)-[:CONTAINS]->(file)
SET file.language = 'java';

MATCH (pkg:Package)
WHERE pkg.project IS NOT NULL
  AND (pkg.language = 'java' OR pkg.language IS NULL)
MERGE (project:Project {name: pkg.project})
MERGE (language:Language {project: pkg.project, name: 'Java'})
  SET language.graphName = 'java'
MERGE (code:Code {project: pkg.project, language: 'java'})
SET code.languageName = 'Java',
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code)
MERGE (code)-[:CONTAINS]->(pkg)
SET pkg.language = 'java';
