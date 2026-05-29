UNWIND $rows AS row
WITH row.caller AS callerSignature,
     row.ownerFqn AS ownerFqn,
     row.calleeName AS calleeName,
     sum(coalesce(row.count, 1)) AS callCount
MATCH (caller:Method {signature: callerSignature, project: $project})
WITH caller, callerSignature, ownerFqn, calleeName, callCount
MERGE (pending:PendingCall {
  project: $project,
  callerSignature: callerSignature,
  calleeOwnerFqn: ownerFqn,
  calleeName: calleeName
})
SET pending.count = callCount
MERGE (caller)-[:PENDING_CALL]->(pending)
