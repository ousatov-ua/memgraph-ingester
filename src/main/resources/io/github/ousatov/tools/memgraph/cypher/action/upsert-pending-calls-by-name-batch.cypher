UNWIND $rows AS row
WITH row.caller AS callerSignature,
     row.ownerFqn AS ownerFqn,
     row.calleeName AS calleeName
MATCH (caller:Method {signature: callerSignature, project: $project})
WITH caller, callerSignature, ownerFqn, calleeName
MERGE (pending:PendingCall {
  project: $project,
  callerSignature: callerSignature,
  calleeOwnerFqn: ownerFqn,
  calleeName: calleeName
})
MERGE (caller)-[:PENDING_CALL]->(pending)
