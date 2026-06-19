UNWIND $rows AS row
WITH row.caller AS callerSignature,
     row.ownerFqn AS ownerFqn,
     row.calleeName AS calleeName,
     sum(coalesce(row.count, 1)) AS callCount
MATCH (caller:Method {signature: callerSignature, project: $project})
OPTIONAL MATCH (owner {fqn: ownerFqn, project: $project})-[:DECLARES]->(directCallee:Method {name: calleeName, project: $project})
WITH caller, callerSignature, ownerFqn, calleeName, callCount, collect(DISTINCT directCallee) AS directCandidates
WITH caller, callerSignature, ownerFqn, calleeName, callCount, directCandidates,
     CASE WHEN size(directCandidates) = 1 THEN directCandidates[0] ELSE null END AS directCallee
FOREACH (_ IN CASE WHEN directCallee IS NULL THEN [] ELSE [1] END |
  MERGE (caller)-[call:CALLS]->(directCallee)
  SET call.count = coalesce(call.count, 0) + callCount
)
FOREACH (_ IN CASE WHEN size(directCandidates) = 0 THEN [1] ELSE [] END |
  MERGE (pending:PendingCall {
    project: $project,
    callerSignature: callerSignature,
    calleeOwnerFqn: ownerFqn,
    calleeName: calleeName
  })
  SET pending.count = callCount,
      pending.allowNameOnly = false
  MERGE (caller)-[:PENDING_CALL]->(pending)
)
