MERGE (proj:Project {name: $project})
MERGE (code:Code {project: $project})
  SET code.sourceRoots  = CASE
        WHEN $sourceRoot IN coalesce(code.sourceRoots, [])
        THEN coalesce(code.sourceRoots, [])
        ELSE coalesce(code.sourceRoots, []) + $sourceRoot
      END,
      code.lastIngested = timestamp()
MERGE (proj)-[:CONTAINS]->(code)
MERGE (memory:Memory {project: $project})
MERGE (proj)-[:HAS_MEMORY]->(memory)
