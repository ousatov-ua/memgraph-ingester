MATCH (pending:PendingCall {project: $project})
MATCH (caller:Method {signature: pending.callerSignature, project: $project})
OPTIONAL MATCH (pendingClass:Class {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*1..]->(changedClass:Class {project: $project})
WHERE changedClass.fqn IN $ownerFqns
OPTIONAL MATCH (pendingInterface:Interface {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*1..]->(changedInterface:Interface {project: $project})
WHERE changedInterface.fqn IN $ownerFqns
OPTIONAL MATCH (pendingImplementor:Class {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*0..]->(:Class {project: $project})-[:IMPLEMENTS]->(:Interface {project: $project})-[:EXTENDS*0..]->(changedImplementedInterface:Interface {project: $project})
WHERE changedImplementedInterface.fqn IN $ownerFqns
WITH pending, caller,
     pending.callerSignature IN $callerSignatures AS callerChanged,
     pending.calleeOwnerFqn IN $ownerFqns AS ownerChanged,
     count(DISTINCT changedClass) AS changedClassAncestors,
     count(DISTINCT changedInterface) AS changedInterfaceAncestors,
     count(DISTINCT changedImplementedInterface) AS changedImplementedInterfaces
WHERE callerChanged
  OR ownerChanged
  OR changedClassAncestors > 0
  OR changedInterfaceAncestors > 0
  OR changedImplementedInterfaces > 0
OPTIONAL MATCH (owner {fqn: pending.calleeOwnerFqn, project: $project})-[:DECLARES]->(directCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, coalesce(pending.count, 1) AS callCount, collect(DISTINCT directCallee) AS directCandidates
OPTIONAL MATCH classPath = (classOwner:Class {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*1..]->(declClass:Class {project: $project})-[:DECLARES]->(classCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, callCount, directCandidates, declClass, size(nodes(classPath)) AS classDepth
ORDER BY classDepth
WITH pending, caller, callCount, directCandidates, collect(DISTINCT declClass) AS declaringClasses
WITH pending, caller, callCount, directCandidates,
     CASE WHEN size(declaringClasses) = 0 THEN null ELSE declaringClasses[0] END AS nearestClass
OPTIONAL MATCH (nearestClass)-[:DECLARES]->(classCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, callCount, directCandidates, collect(DISTINCT classCallee) AS classCandidates
OPTIONAL MATCH (classOwner:Class {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*0..]->(:Class {project: $project})-[:IMPLEMENTS]->(:Interface {project: $project})-[:EXTENDS*0..]->(:Interface {project: $project})-[:DECLARES]->(classInterfaceCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, callCount, directCandidates, classCandidates,
     collect(DISTINCT classInterfaceCallee) AS classInterfaceCandidates
OPTIONAL MATCH (interfaceOwner:Interface {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*1..]->(:Interface {project: $project})-[:DECLARES]->(interfaceCallee:Method {name: pending.calleeName, project: $project})
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
