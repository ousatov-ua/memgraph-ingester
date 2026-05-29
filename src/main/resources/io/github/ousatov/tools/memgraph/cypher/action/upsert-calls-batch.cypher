UNWIND $rows AS row
WITH row.caller AS callerSignature,
     row.callee AS calleeSignature,
     sum(coalesce(row.count, 1)) AS callCount
MATCH (caller:Method {signature: callerSignature, project: $project})
WITH caller, calleeSignature, callCount
MERGE (callee:Method {signature: calleeSignature, project: $project})
MERGE (caller)-[call:CALLS]->(callee)
SET call.count = callCount
