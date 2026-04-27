MERGE (parent:Interface {fqn: $parent, project: $project})
WITH parent
MATCH (child {fqn: $child, project: $project})
MERGE (child)-[:EXTENDS]->(parent)
