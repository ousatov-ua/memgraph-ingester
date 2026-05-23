MERGE (proj:Project {name: $project})
MERGE (language:Language {project: $project, name: $languageName})
  SET language.graphName = $language
MERGE (code:Code {project: $project, language: $language})
  SET code.sourceRoots  = CASE
        WHEN $sourceRoot IN coalesce(code.sourceRoots, [])
        THEN coalesce(code.sourceRoots, [])
        ELSE coalesce(code.sourceRoots, []) + $sourceRoot
      END,
      code.languageName = $languageName,
      code.lastIngested = timestamp()
MERGE (proj)-[:CONTAINS]->(language)
MERGE (language)-[:CONTAINS]->(code)
MERGE (memory:Memory {project: $project})
MERGE (proj)-[:HAS_MEMORY]->(memory)
