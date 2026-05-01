MERGE (parent:Interface {fqn: $parent, project: $project})
  SET parent.name = coalesce(parent.name, $parentName),
      parent.packageName = coalesce(parent.packageName, $parentPkg),
      parent.isExternal = coalesce(parent.isExternal, true)
WITH parent
MATCH (child:Interface {fqn: $child, project: $project})
MERGE (child)-[:EXTENDS]->(parent)
