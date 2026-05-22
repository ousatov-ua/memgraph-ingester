MATCH (pending:PendingCall {project: $project})
MATCH (caller:Method {signature: pending.callerSignature, project: $project})
OPTIONAL MATCH (owner {fqn: pending.calleeOwnerFqn, project: $project})
  -[:DECLARES]->(callee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, collect(callee) AS candidates
WHERE size(candidates) = 1
WITH pending, caller, candidates[0] AS callee
MERGE (caller)-[:CALLS]->(callee)
DETACH DELETE pending
