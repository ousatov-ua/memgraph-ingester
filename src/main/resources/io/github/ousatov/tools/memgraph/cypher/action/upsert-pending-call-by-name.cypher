MATCH (caller:Method {signature: $caller, project: $project})
MERGE (pending:PendingCall {
  project: $project,
  callerSignature: $caller,
  calleeOwnerFqn: $ownerFqn,
  calleeName: $calleeName
})
MERGE (caller)-[:PENDING_CALL]->(pending)
