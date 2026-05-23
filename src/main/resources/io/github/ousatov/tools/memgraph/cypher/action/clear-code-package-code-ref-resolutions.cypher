MATCH (ref:CodeRef {project: $project})-[old:RESOLVES_TO]->()
WHERE ref.targetType IN ['Code', 'Package']
DELETE old
