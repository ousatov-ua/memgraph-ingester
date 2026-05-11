MATCH (caller:Method {signature: $caller, project: $project})
MERGE (callee:Method {signature: $callee, project: $project})
MERGE (caller)-[:CALLS]->(callee)
