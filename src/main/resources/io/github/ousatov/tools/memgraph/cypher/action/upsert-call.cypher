MATCH (caller:Method {signature: $caller, project: $project})
MATCH (callee:Method {signature: $callee, project: $project})
MERGE (caller)-[:CALLS]->(callee)
