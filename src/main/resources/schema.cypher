// ============================================================
// Memgraph schema for a Java codebase knowledge graph
// Run once against your Memgraph instance before ingestion.
// ============================================================

// ---------- Uniqueness constraints ----------
CREATE CONSTRAINT ON (p:Package)   ASSERT p.name IS UNIQUE;
CREATE CONSTRAINT ON (c:Class)     ASSERT c.fqn  IS UNIQUE;
CREATE CONSTRAINT ON (i:Interface) ASSERT i.fqn  IS UNIQUE;
CREATE CONSTRAINT ON (m:Method)    ASSERT m.signature IS UNIQUE;
CREATE CONSTRAINT ON (f:Field)     ASSERT f.fqn  IS UNIQUE;
CREATE CONSTRAINT ON (file:File)   ASSERT file.path IS UNIQUE;

// ---------- Lookup indexes ----------
CREATE INDEX ON :Class(name);
CREATE INDEX ON :Interface(name);
CREATE INDEX ON :Method(name);
CREATE INDEX ON :Field(name);
CREATE INDEX ON :Class(packageName);

// ============================================================
// Node & relationship model (for reference)
// ============================================================
//
// Nodes
//   (:Package   {name})
//   (:File      {path, lastModified})
//   (:Class     {fqn, name, packageName, isAbstract, visibility})
//   (:Interface {fqn, name, packageName})
//   (:Method    {signature, name, returnType, visibility, isStatic,
//                paramTypes, startLine, endLine})
//   (:Field     {fqn, name, type, visibility, isStatic})
//
// Relationships
//   (Package)-[:CONTAINS]->(Class|Interface)
//   (File)-[:DEFINES]->(Class|Interface)
//   (Class)-[:EXTENDS]->(Class)
//   (Class)-[:IMPLEMENTS]->(Interface)
//   (Interface)-[:EXTENDS]->(Interface)
//   (Class|Interface)-[:DECLARES]->(Method|Field)
//   (Method)-[:CALLS]->(Method)
//   (Method)-[:READS|WRITES]->(Field)
//   (Class)-[:IMPORTS]->(Class|Interface)
//   (Class)-[:ANNOTATED_WITH]->(Class)
// ============================================================
