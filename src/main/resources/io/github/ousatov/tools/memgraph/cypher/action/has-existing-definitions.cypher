MATCH (definition {project: $project})
WHERE (definition:Class AND definition.fqn IN $classFqns)
  OR (definition:Interface AND definition.fqn IN $interfaceFqns)
  OR (definition:Annotation AND definition.fqn IN $annotationFqns)
  OR (definition:Method AND definition.signature IN $methodSignatures)
  OR (definition:Field AND definition.fqn IN $fieldFqns)
WITH count(definition) AS definitions
RETURN definitions
