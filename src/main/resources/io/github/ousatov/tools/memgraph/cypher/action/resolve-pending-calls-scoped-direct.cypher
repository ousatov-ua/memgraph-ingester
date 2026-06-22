CALL {
  WITH $callerSignatures AS callerSignatures
  UNWIND callerSignatures AS callerSignature
  MATCH (pending:PendingCall {callerSignature: callerSignature, project: $project})
  RETURN pending
  UNION
  WITH $ownerFqns AS ownerFqns
  UNWIND ownerFqns AS ownerFqn
  MATCH (pending:PendingCall {calleeOwnerFqn: ownerFqn, project: $project})
  RETURN pending
}
WITH DISTINCT pending
MATCH (caller:Method {signature: pending.callerSignature, project: $project})
OPTIONAL MATCH (owner {fqn: pending.calleeOwnerFqn, project: $project})-[:DECLARES]->(directCallee:Method {name: pending.calleeName, project: $project})
WITH pending, caller, coalesce(pending.count, 1) AS callCount, collect(DISTINCT directCallee) AS candidates
WHERE size(candidates) = 1
WITH pending, caller, candidates[0] AS callee, callCount
MERGE (caller)-[call:CALLS]->(callee)
SET call.count = coalesce(call.count, 0) + callCount
DETACH DELETE pending
