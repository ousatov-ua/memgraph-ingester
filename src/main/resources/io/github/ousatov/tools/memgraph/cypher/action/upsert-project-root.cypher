MERGE (proj:Project {name: $project})
MERGE (memory:Memory {project: $project})
MERGE (proj)-[:HAS_MEMORY]->(memory)
