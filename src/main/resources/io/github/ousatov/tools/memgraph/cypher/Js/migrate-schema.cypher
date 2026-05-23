MATCH (code:Code {language: 'js'})
WHERE code.project IS NOT NULL
MERGE (project:Project {name: code.project})
MERGE (language:Language {project: code.project, name: 'Js'})
  SET language.graphName = 'js'
SET code.languageName = 'Js',
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code);

MATCH (file:File {language: 'js'})
WHERE file.project IS NOT NULL
MERGE (project:Project {name: file.project})
MERGE (language:Language {project: file.project, name: 'Js'})
  SET language.graphName = 'js'
MERGE (code:Code {project: file.project, language: 'js'})
SET code.languageName = 'Js',
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code)
MERGE (code)-[:CONTAINS]->(file);

MATCH (pkg:Package)
WHERE pkg.project IS NOT NULL
OPTIONAL MATCH (pkg)-[:CONTAINS]->(declared {language: 'js'})
WITH pkg, count(declared) AS jsDeclarations
WHERE pkg.language = 'js'
  OR (pkg.language IS NULL AND jsDeclarations > 0)
MERGE (project:Project {name: pkg.project})
MERGE (language:Language {project: pkg.project, name: 'Js'})
  SET language.graphName = 'js'
MERGE (code:Code {project: pkg.project, language: 'js'})
SET code.languageName = 'Js',
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code)
MERGE (code)-[:CONTAINS]->(pkg)
SET pkg.language = 'js';
