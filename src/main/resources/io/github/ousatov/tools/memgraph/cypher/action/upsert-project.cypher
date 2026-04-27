MERGE (proj:Project {name: $project})
  SET proj.sourceRoots  = CASE
        WHEN $sourceRoot IN coalesce(proj.sourceRoots, [])
        THEN coalesce(proj.sourceRoots, [])
        ELSE coalesce(proj.sourceRoots, []) + $sourceRoot
      END,
      proj.lastIngested = timestamp()
