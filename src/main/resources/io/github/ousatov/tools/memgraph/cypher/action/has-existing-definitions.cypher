OPTIONAL MATCH (classDefinition:Class {project: $project})
WHERE classDefinition.fqn IN $classFqns
WITH count(classDefinition) AS classDefinitions
OPTIONAL MATCH (interfaceDefinition:Interface {project: $project})
WHERE interfaceDefinition.fqn IN $interfaceFqns
WITH classDefinitions, count(interfaceDefinition) AS interfaceDefinitions
OPTIONAL MATCH (annotationDefinition:Annotation {project: $project})
WHERE annotationDefinition.fqn IN $annotationFqns
WITH classDefinitions, interfaceDefinitions, count(annotationDefinition) AS annotationDefinitions
OPTIONAL MATCH (methodDefinition:Method {project: $project})
WHERE methodDefinition.signature IN $methodSignatures
WITH classDefinitions, interfaceDefinitions, annotationDefinitions, count(methodDefinition) AS methodDefinitions
OPTIONAL MATCH (fieldDefinition:Field {project: $project})
WHERE fieldDefinition.fqn IN $fieldFqns
WITH classDefinitions, interfaceDefinitions, annotationDefinitions, methodDefinitions, count(fieldDefinition) AS fieldDefinitions
RETURN classDefinitions + interfaceDefinitions + annotationDefinitions + methodDefinitions + fieldDefinitions AS definitions
