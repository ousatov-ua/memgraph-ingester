MERGE (i:Interface {fqn: $iface, project: $project})
WITH i
MATCH (c:Class {fqn: $child, project: $project})
MERGE (c)-[:IMPLEMENTS]->(i)
