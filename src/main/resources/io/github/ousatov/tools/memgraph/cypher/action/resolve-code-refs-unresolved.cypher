MATCH (ref:CodeRef {project: $project})-[old:RESOLVES_TO]->()
WHERE NOT ref.targetType IN [
  'Code',
  'Package',
  'File',
  'Class',
  'Interface',
  'Annotation',
  'Method',
  'Field'
]
DELETE old
