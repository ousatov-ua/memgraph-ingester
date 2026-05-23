MATCH (:Language {project: $project, name: $languageName})-[:CONTAINS]->(code:Code {project: $project, language: $language})
MERGE (p:Package {name: $name, project: $project, language: $language})
MERGE (code)-[:CONTAINS]->(p)
