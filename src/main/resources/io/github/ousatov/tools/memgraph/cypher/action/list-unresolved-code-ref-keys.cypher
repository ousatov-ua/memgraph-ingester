MATCH (ref:CodeRef {project: $project})
WHERE NOT (ref)-[:RESOLVES_TO]->()
  AND ref.targetType IN [
    'Code',
    'Package',
    'File',
    'Class',
    'Interface',
    'Annotation',
    'Method',
    'Field'
  ]
  AND ref.key IS NOT NULL
RETURN ref.targetType AS targetType, ref.key AS key
ORDER BY targetType, key
