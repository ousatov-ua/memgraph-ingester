UNWIND $rows AS row
WITH row.caller AS callerSignature,
     row.ownerFqn AS ownerFqn,
     row.calleeName AS calleeName,
     sum(coalesce(row.count, 1)) AS callCount,
     max(CASE WHEN coalesce(row.allowNameOnly, false) THEN 1 ELSE 0 END) AS allowNameOnlyCount
MATCH (caller:Method {signature: callerSignature, project: $project})
WITH caller, callerSignature, ownerFqn, calleeName, callCount, allowNameOnlyCount
MERGE (pending:PendingCall {
  project: $project,
  callerSignature: callerSignature,
  calleeOwnerFqn: ownerFqn,
  calleeName: calleeName
})
SET pending.count = callCount,
    pending.allowNameOnly = CASE WHEN allowNameOnlyCount > 0 THEN true ELSE false END
MERGE (caller)-[:PENDING_CALL]->(pending)
