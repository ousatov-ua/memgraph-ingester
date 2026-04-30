MERGE (parent:Class {fqn: $parent, project: $project})
WITH parent
MATCH (child:Class {fqn: $child, project: $project})
MERGE (child)-[:EXTENDS]->(parent)
