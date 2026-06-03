UNWIND $paths AS path
MATCH (f:File {path: path, project: $project})
SET f.retainedSourceToken = $retainedSourceToken
