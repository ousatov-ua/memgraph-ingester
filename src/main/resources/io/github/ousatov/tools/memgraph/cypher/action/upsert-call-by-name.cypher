MATCH (caller:Method {signature: $caller, project: $project})
OPTIONAL MATCH (owner {fqn: $ownerFqn, project: $project})-[:DECLARES]->(directCallee:Method {name: $calleeName, project: $project})
WITH caller, collect(DISTINCT directCallee) AS directCandidates
OPTIONAL MATCH classPath = (classOwner:Class {fqn: $ownerFqn, project: $project})-[:EXTENDS*1..]->(declClass:Class {project: $project})-[:DECLARES]->(classCallee:Method {name: $calleeName, project: $project})
WITH caller, directCandidates, declClass, size(nodes(classPath)) AS classDepth
ORDER BY classDepth
WITH caller, directCandidates, collect(DISTINCT declClass) AS declaringClasses
WITH caller, directCandidates,
     CASE WHEN size(declaringClasses) = 0 THEN null ELSE declaringClasses[0] END AS nearestClass
OPTIONAL MATCH (nearestClass)-[:DECLARES]->(classCallee:Method {name: $calleeName, project: $project})
WITH caller, directCandidates, collect(DISTINCT classCallee) AS classCandidates
OPTIONAL MATCH (classOwner:Class {fqn: $ownerFqn, project: $project})-[:EXTENDS*0..]->(:Class {project: $project})-[:IMPLEMENTS]->(:Interface {project: $project})-[:EXTENDS*0..]->(:Interface {project: $project})-[:DECLARES]->(classInterfaceCallee:Method {name: $calleeName, project: $project})
WITH caller, directCandidates, classCandidates, collect(DISTINCT classInterfaceCallee) AS classInterfaceCandidates
OPTIONAL MATCH (interfaceOwner:Interface {fqn: $ownerFqn, project: $project})-[:EXTENDS*1..]->(:Interface {project: $project})-[:DECLARES]->(interfaceCallee:Method {name: $calleeName, project: $project})
WITH caller, directCandidates, classCandidates, classInterfaceCandidates,
     collect(DISTINCT interfaceCallee) AS interfaceCandidates
WITH caller, directCandidates, classCandidates,
     classInterfaceCandidates + interfaceCandidates AS inheritedInterfaceCandidates
WITH caller,
     CASE
       WHEN size(directCandidates) > 0 THEN directCandidates
       WHEN size(classCandidates) > 0 THEN classCandidates
       ELSE inheritedInterfaceCandidates
     END AS candidates
WHERE size(candidates) = 1
WITH caller, candidates[0] AS callee
MERGE (caller)-[:CALLS]->(callee)
