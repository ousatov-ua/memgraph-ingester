MERGE (parent:Interface {fqn: $parent, project: $project})
WITH parent
MATCH (child:Interface {fqn: $child, project: $project})
MERGE (child)-[:EXTENDS]->(parent)
