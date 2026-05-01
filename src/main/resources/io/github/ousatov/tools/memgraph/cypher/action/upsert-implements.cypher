MERGE (i:Interface {fqn: $iface, project: $project})
  SET i.name = coalesce(i.name, $ifaceName),
      i.packageName = coalesce(i.packageName, $ifacePkg),
      i.isExternal = coalesce(i.isExternal, true)
WITH i
MATCH (c:Class {fqn: $child, project: $project})
MERGE (c)-[:IMPLEMENTS]->(i)
