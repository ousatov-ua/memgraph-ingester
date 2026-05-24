UNWIND $rows AS row
WITH row.caller AS callerSignature, row.callee AS calleeSignature
MATCH (caller:Method {signature: callerSignature, project: $project})
WITH caller, calleeSignature
MERGE (callee:Method {signature: calleeSignature, project: $project})
MERGE (caller)-[:CALLS]->(callee)
