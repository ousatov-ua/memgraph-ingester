// ============================================================
// Memgraph schema for a Java codebase knowledge graph
// Multi-project: uniqueness keys include `project` so that
// multiple codebases can coexist in one Memgraph instance.
// Run once against your Memgraph instance before ingestion.
// ============================================================

// ---------- Uniqueness constraints (composite with project) ----------
CREATE CONSTRAINT ON (p:Package)   ASSERT p.name, p.project      IS UNIQUE;
CREATE CONSTRAINT ON (c:Class)     ASSERT c.fqn, c.project       IS UNIQUE;
CREATE CONSTRAINT ON (i:Interface) ASSERT i.fqn, i.project       IS UNIQUE;
CREATE CONSTRAINT ON (m:Method)    ASSERT m.signature, m.project IS UNIQUE;
CREATE CONSTRAINT ON (f:Field)     ASSERT f.fqn, f.project       IS UNIQUE;
CREATE CONSTRAINT ON (file:File)   ASSERT file.path, file.project IS UNIQUE;

// ---------- Existence constraints: REMOVED ----------
// These were causing issues when ingesting partial graphs or when
// external types are referenced without a project. The composite
// uniqueness constraints above are enough to prevent collisions.
// The ingester always sets `project` anyway.

// ---------- Lookup indexes ----------
CREATE INDEX ON :Class(project);
CREATE INDEX ON :Class(name);
CREATE INDEX ON :Interface(project);
CREATE INDEX ON :Interface(name);
CREATE INDEX ON :Method(project);
CREATE INDEX ON :Method(name);
CREATE INDEX ON :Field(project);
CREATE INDEX ON :Field(name);
CREATE INDEX ON :Class(packageName);

// ============================================================
// Node & relationship model (for reference)
// ============================================================
//
// Every node carries a `project` property. Queries should ALWAYS
// filter by project unless explicitly exploring cross-project data.
//
// Nodes
//   (:Package   {name, project})
//   (:File      {path, project, lastModified})
//   (:Class     {fqn, project, name, packageName, isAbstract, visibility})
//   (:Interface {fqn, project, name, packageName})
//   (:Method    {signature, project, name, returnType, visibility,
//                isStatic, startLine, endLine})
//   (:Field     {fqn, project, name, type, visibility, isStatic})
//
// Relationships
//   (Package)-[:CONTAINS]->(Class|Interface)
//   (File)-[:DEFINES]->(Class|Interface)
//   (Class)-[:EXTENDS]->(Class)
//   (Class)-[:IMPLEMENTS]->(Interface)
//   (Interface)-[:EXTENDS]->(Interface)
//   (Class|Interface)-[:DECLARES]->(Method|Field)
//   (Method)-[:CALLS]->(Method)
// ============================================================