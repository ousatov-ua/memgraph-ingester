MATCH (pending:PendingCall {project: $project})
MATCH (caller:Method {signature: pending.callerSignature, project: $project})
OPTIONAL MATCH (owner {fqn: pending.calleeOwnerFqn, project: $project})-[:DECLARES]->(directCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, collect(DISTINCT directCallee) AS directCandidates
OPTIONAL MATCH classPath = (classOwner:Class {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*1..]->(declClass:Class {project: $project})-[:DECLARES]->(classCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, directCandidates, declClass, size(nodes(classPath)) AS classDepth
ORDER BY classDepth
WITH pending, caller, directCandidates, collect(DISTINCT declClass) AS declaringClasses
WITH pending, caller, directCandidates,
     CASE WHEN size(declaringClasses) = 0 THEN null ELSE declaringClasses[0] END AS nearestClass
OPTIONAL MATCH (nearestClass)-[:DECLARES]->(classCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, directCandidates, collect(DISTINCT classCallee) AS classCandidates
OPTIONAL MATCH (classOwner:Class {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*0..]->(:Class {project: $project})-[:IMPLEMENTS]->(:Interface {project: $project})-[:EXTENDS*0..]->(:Interface {project: $project})-[:DECLARES]->(classInterfaceCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, directCandidates, classCandidates,
     collect(DISTINCT classInterfaceCallee) AS classInterfaceCandidates
OPTIONAL MATCH (interfaceOwner:Interface {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*1..]->(:Interface {project: $project})-[:DECLARES]->(interfaceCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, directCandidates, classCandidates, classInterfaceCandidates,
     collect(DISTINCT interfaceCallee) AS interfaceCandidates
WITH pending, caller,
     CASE WHEN size(directCandidates) > 0
          THEN directCandidates
          ELSE CASE WHEN size(classCandidates) > 0
                    THEN classCandidates
                    ELSE classInterfaceCandidates + interfaceCandidates
               END
     END AS candidates
WHERE size(candidates) = 1
WITH pending, caller, candidates[0] AS callee
MERGE (caller)-[:CALLS]->(callee)
DETACH DELETE pending
