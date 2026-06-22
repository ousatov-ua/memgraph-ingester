CALL {
  WITH $ownerFqns AS ownerFqns
  MATCH (changedClass:Class {project: $project})
  WHERE changedClass.fqn IN ownerFqns
  MATCH (affectedClass:Class {project: $project})-[:EXTENDS*0..]->(changedClass)
  RETURN collect(DISTINCT affectedClass.fqn) AS classOwnerFqns
}
CALL {
  WITH $ownerFqns AS ownerFqns
  MATCH (changedInterface:Interface {project: $project})
  WHERE changedInterface.fqn IN ownerFqns
  MATCH (affectedInterface:Interface {project: $project})-[:EXTENDS*0..]->(changedInterface)
  RETURN collect(DISTINCT affectedInterface.fqn) AS interfaceOwnerFqns
}
CALL {
  WITH $ownerFqns AS ownerFqns
  MATCH (changedInterface:Interface {project: $project})
  WHERE changedInterface.fqn IN ownerFqns
  MATCH (affectedClass:Class {project: $project})-[:EXTENDS*0..]->(:Class {project: $project})-[:IMPLEMENTS]->(:Interface {project: $project})-[:EXTENDS*0..]->(changedInterface)
  RETURN collect(DISTINCT affectedClass.fqn) AS implementorOwnerFqns
}
WITH classOwnerFqns + interfaceOwnerFqns + implementorOwnerFqns AS affectedOwnerFqns
CALL {
  WITH $callerSignatures AS callerSignatures
  UNWIND callerSignatures AS callerSignature
  MATCH (pending:PendingCall {callerSignature: callerSignature, project: $project})
  RETURN pending
  UNION
  WITH affectedOwnerFqns
  UNWIND affectedOwnerFqns AS ownerFqn
  MATCH (pending:PendingCall {calleeOwnerFqn: ownerFqn, project: $project})
  RETURN pending
  UNION
  WITH $methodNames AS methodNames
  UNWIND methodNames AS methodName
  MATCH (pending:PendingCall {calleeOwnerFqn: '', calleeName: methodName, project: $project})
  WHERE coalesce(pending.allowNameOnly, false) = true
  RETURN pending
}
WITH DISTINCT pending
MATCH (caller:Method {signature: pending.callerSignature, project: $project})
OPTIONAL MATCH (owner {fqn: pending.calleeOwnerFqn, project: $project})-[:DECLARES]->(directCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, coalesce(pending.count, 1) AS callCount, collect(DISTINCT directCallee) AS directCandidates
OPTIONAL MATCH classPath = (classOwner:Class {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*1..]->(declClass:Class {project: $project})-[:DECLARES]->(classCallee:Method {name: pending.calleeName, project: $project})
WHERE size(directCandidates) = 0
WITH pending, caller, callCount, directCandidates, declClass, size(nodes(classPath)) AS classDepth
ORDER BY classDepth
WITH pending, caller, callCount, directCandidates, collect(DISTINCT declClass) AS declaringClasses
WITH pending, caller, callCount, directCandidates,
     CASE WHEN size(declaringClasses) = 0 THEN null ELSE declaringClasses[0] END AS nearestClass
OPTIONAL MATCH (nearestClass)-[:DECLARES]->(classCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, callCount, directCandidates, collect(DISTINCT classCallee) AS classCandidates
OPTIONAL MATCH (classOwner:Class {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*0..]->(:Class {project: $project})-[:IMPLEMENTS]->(:Interface {project: $project})-[:EXTENDS*0..]->(:Interface {project: $project})-[:DECLARES]->(classInterfaceCallee:Method {name: pending.calleeName, project: $project})
WHERE size(directCandidates) = 0 AND size(classCandidates) = 0
WITH pending, caller, callCount, directCandidates, classCandidates,
     collect(DISTINCT classInterfaceCallee) AS classInterfaceCandidates
OPTIONAL MATCH (interfaceOwner:Interface {fqn: pending.calleeOwnerFqn, project: $project})-[:EXTENDS*1..]->(:Interface {project: $project})-[:DECLARES]->(interfaceCallee:Method {name: pending.calleeName, project: $project})
WHERE size(directCandidates) = 0 AND size(classCandidates) = 0
WITH pending, caller, callCount, directCandidates, classCandidates, classInterfaceCandidates,
     collect(DISTINCT interfaceCallee) AS interfaceCandidates
WITH pending, caller, callCount, directCandidates, classCandidates,
     classInterfaceCandidates + interfaceCandidates AS inheritedInterfaceCandidates
OPTIONAL MATCH (nameOnlyCallee:Method {name: pending.calleeName, project: $project})
WHERE pending.calleeOwnerFqn = ''
  AND coalesce(pending.allowNameOnly, false) = true
  AND size(directCandidates) = 0
  AND size(classCandidates) = 0
  AND size(inheritedInterfaceCandidates) = 0
  AND nameOnlyCallee.startLine IS NOT NULL
  AND nameOnlyCallee <> caller
WITH pending, caller, callCount, directCandidates, classCandidates, inheritedInterfaceCandidates,
     collect(DISTINCT nameOnlyCallee) AS nameOnlyCandidates
WITH pending, caller, callCount,
     CASE
       WHEN size(directCandidates) > 0 THEN directCandidates
       WHEN size(classCandidates) > 0 THEN classCandidates
       WHEN size(inheritedInterfaceCandidates) > 0 THEN inheritedInterfaceCandidates
       ELSE nameOnlyCandidates
     END AS candidates
WHERE size(candidates) = 1
WITH pending, caller, candidates[0] AS callee, callCount
MERGE (caller)-[call:CALLS]->(callee)
SET call.count = coalesce(call.count, 0) + callCount
DETACH DELETE pending
