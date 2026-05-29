UNWIND $rows AS row
WITH row.child AS childFqn,
     row.target AS parentFqn,
     row.targetName AS parentName,
     row.targetPkg AS parentPkg,
     row.language AS language
MERGE (parent:Interface {fqn: parentFqn, project: $project})
  SET parent.name = coalesce(parent.name, parentName),
      parent.packageName = coalesce(parent.packageName, parentPkg),
      parent.language = coalesce(parent.language, language),
      parent.isExternal = coalesce(parent.isExternal, true)
WITH parent, childFqn
MATCH (child:Interface {fqn: childFqn, project: $project})
MERGE (child)-[:EXTENDS]->(parent)
