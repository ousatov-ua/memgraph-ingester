MATCH (pending:PendingCall {project: $project})
MATCH (caller:Method {signature: pending.callerSignature, project: $project})
OPTIONAL MATCH (owner {fqn: pending.calleeOwnerFqn, project: $project})-[:DECLARES]->(directCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, coalesce(pending.count, 1) AS callCount, collect(DISTINCT directCallee) AS directCandidates
OPTIONAL MATCH classPath = (classOwner:Class {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*1..16]->(declClass:Class {project: $project})-[:DECLARES]->(classCallee:Method {name: pending.calleeName, project: $project})
WHERE size(directCandidates) = 0
WITH pending, caller, callCount, directCandidates, declClass, size(nodes(classPath)) AS classDepth
ORDER BY classDepth
WITH pending, caller, callCount, directCandidates, collect(DISTINCT declClass) AS declaringClasses
WITH pending, caller, callCount, directCandidates,
     CASE WHEN size(declaringClasses) = 0 THEN null ELSE declaringClasses[0] END AS nearestClass
OPTIONAL MATCH (nearestClass)-[:DECLARES]->(classCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, callCount, directCandidates, collect(DISTINCT classCallee) AS classCandidates
OPTIONAL MATCH (classOwner:Class {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*0..16]->(:Class {project: $project})-[:IMPLEMENTS]->(:Interface {project: $project})-[:EXTENDS*0..16]->(:Interface {project: $project})-[:DECLARES]->(classInterfaceCallee:Method {name: pending.calleeName, project: $project})
WHERE size(directCandidates) = 0 AND size(classCandidates) = 0
WITH pending, caller, callCount, directCandidates, classCandidates,
     collect(DISTINCT classInterfaceCallee) AS classInterfaceCandidates
OPTIONAL MATCH (interfaceOwner:Interface {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*1..16]->(:Interface {project: $project})-[:DECLARES]->(interfaceCallee:Method {name: pending.calleeName, project: $project})
WHERE size(directCandidates) = 0 AND size(classCandidates) = 0
WITH pending, caller, callCount, directCandidates, classCandidates, classInterfaceCandidates,
     collect(DISTINCT interfaceCallee) AS interfaceCandidates
WITH pending, caller, callCount, directCandidates, classCandidates,
     classInterfaceCandidates + interfaceCandidates AS inheritedInterfaceCandidates
WITH pending, caller, callCount,
     CASE
       WHEN size(directCandidates) > 0 THEN directCandidates
       WHEN size(classCandidates) > 0 THEN classCandidates
       ELSE inheritedInterfaceCandidates
     END AS candidates
WHERE size(candidates) = 1
WITH pending, caller, candidates[0] AS callee, callCount
MERGE (caller)-[call:CALLS]->(callee)
SET call.count = coalesce(call.count, 0) + callCount
DETACH DELETE pending
