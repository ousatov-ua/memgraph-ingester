UNWIND $paths AS path
MATCH (f:File {path: path, project: $project, language: 'java'})
WHERE coalesce(f.analysisCacheKey, '') = $analysisCacheKey
RETURN f.path AS path, f.lastModified AS lastModified
