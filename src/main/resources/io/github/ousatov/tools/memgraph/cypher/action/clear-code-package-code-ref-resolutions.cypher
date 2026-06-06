MATCH (ref:CodeRef {project: $project, targetType: 'Package'})-[old:RESOLVES_TO]->()
WHERE NOT ref.key CONTAINS ':'
DELETE old
