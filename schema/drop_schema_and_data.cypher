// 1. Drop all constraints (so the wipe doesn't complain)
DROP CONSTRAINT ON (p:Package)   ASSERT p.name, p.project       IS UNIQUE;
DROP CONSTRAINT ON (c:Class)     ASSERT c.fqn, c.project        IS UNIQUE;
DROP CONSTRAINT ON (i:Interface) ASSERT i.fqn, i.project        IS UNIQUE;
DROP CONSTRAINT ON (m:Method)    ASSERT m.signature, m.project  IS UNIQUE;
DROP CONSTRAINT ON (f:Field)     ASSERT f.fqn, f.project        IS UNIQUE;
DROP CONSTRAINT ON (file:File)   ASSERT file.path, file.project IS UNIQUE;

DROP CONSTRAINT ON (c:Class)     ASSERT EXISTS (c.project);
DROP CONSTRAINT ON (i:Interface) ASSERT EXISTS (i.project);
DROP CONSTRAINT ON (m:Method)    ASSERT EXISTS (m.project);
DROP CONSTRAINT ON (f:Field)     ASSERT EXISTS (f.project);
DROP CONSTRAINT ON (p:Package)   ASSERT EXISTS (p.project);
DROP CONSTRAINT ON (file:File)   ASSERT EXISTS (file.project);

// 2. Drop leftover single-property constraints from the old schema if present
DROP CONSTRAINT ON (p:Package)   ASSERT p.name IS UNIQUE;
DROP CONSTRAINT ON (c:Class)     ASSERT c.fqn IS UNIQUE;
DROP CONSTRAINT ON (i:Interface) ASSERT i.fqn IS UNIQUE;
DROP CONSTRAINT ON (m:Method)    ASSERT m.signature IS UNIQUE;
DROP CONSTRAINT ON (f:Field)     ASSERT f.fqn IS UNIQUE;
DROP CONSTRAINT ON (file:File)   ASSERT file.path IS UNIQUE;

// 3. Wipe all data
MATCH (n) DETACH DELETE n;

// 4. Re-apply the schema
// (paste the contents of schema.cypher here)