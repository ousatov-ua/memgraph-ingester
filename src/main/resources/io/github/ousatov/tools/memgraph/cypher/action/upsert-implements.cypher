MERGE (i:Interface {fqn: $iface, project: $project})
  SET i.name = coalesce(i.name, $ifaceName),
      i.packageName = coalesce(i.packageName, $ifacePkg),
      i.language = coalesce(i.language, $language),
      i.isExternal = coalesce(i.isExternal, true)
WITH i
MATCH (c:Class {fqn: $child, project: $project})
MERGE (c)-[:IMPLEMENTS]->(i)
