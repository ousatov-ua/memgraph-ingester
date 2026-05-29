UNWIND $rows AS row
WITH row.child AS childFqn,
     row.target AS ifaceFqn,
     row.targetName AS ifaceName,
     row.targetPkg AS ifacePkg,
     row.language AS language
MERGE (i:Interface {fqn: ifaceFqn, project: $project})
  SET i.name = coalesce(i.name, ifaceName),
      i.packageName = coalesce(i.packageName, ifacePkg),
      i.language = coalesce(i.language, language),
      i.isExternal = coalesce(i.isExternal, true)
WITH i, childFqn
MATCH (c:Class {fqn: childFqn, project: $project})
MERGE (c)-[:IMPLEMENTS]->(i)
