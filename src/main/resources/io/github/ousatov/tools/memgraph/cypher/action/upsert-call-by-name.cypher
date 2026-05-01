MATCH (caller:Method {signature: $caller, project: $project})
MATCH (owner {fqn: $ownerFqn, project: $project})-[:DECLARES]->(callee:Method {name: $calleeName, project: $project})
WITH caller, collect(callee) AS candidates
WHERE size(candidates) = 1
WITH caller, candidates[0] AS callee
MERGE (caller)-[:CALLS]->(callee)
