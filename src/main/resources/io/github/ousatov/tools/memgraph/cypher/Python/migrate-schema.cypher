MATCH (code:Code {language: 'python'})
WHERE code.project IS NOT NULL
MERGE (project:Project {name: code.project})
MERGE (language:Language {project: code.project, name: 'Python'})
  SET language.graphName = 'python'
SET code.languageName = 'Python',
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code);

MATCH (file:File {language: 'python'})
WHERE file.project IS NOT NULL
MERGE (project:Project {name: file.project})
MERGE (language:Language {project: file.project, name: 'Python'})
  SET language.graphName = 'python'
MERGE (code:Code {project: file.project, language: 'python'})
SET code.languageName = 'Python',
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code)
MERGE (code)-[:CONTAINS]->(file);

MATCH (pkg:Package)
WHERE pkg.project IS NOT NULL
OPTIONAL MATCH (pkg)-[:CONTAINS]->(declared {language: 'python'})
WITH pkg, count(declared) AS pythonDeclarations
WHERE pkg.language = 'python'
  OR (pkg.language IS NULL AND pythonDeclarations > 0)
MERGE (project:Project {name: pkg.project})
MERGE (language:Language {project: pkg.project, name: 'Python'})
  SET language.graphName = 'python'
MERGE (code:Code {project: pkg.project, language: 'python'})
SET code.languageName = 'Python',
    code.lastIngested = coalesce(code.lastIngested, timestamp())
MERGE (project)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code)
MERGE (code)-[:CONTAINS]->(pkg)
SET pkg.language = 'python';
