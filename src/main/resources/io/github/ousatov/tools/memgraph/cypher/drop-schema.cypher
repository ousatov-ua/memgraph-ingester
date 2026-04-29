// =====================================================
// DROP MEMORY CONSTRAINTS
// =====================================================

DROP CONSTRAINT ON (m:Memory) ASSERT m.project IS UNIQUE;

DROP CONSTRAINT ON (d:Decision) ASSERT d.id, d.project IS UNIQUE;
DROP CONSTRAINT ON (i:Idea) ASSERT i.id, i.project IS UNIQUE;
DROP CONSTRAINT ON (c:Context) ASSERT c.id, c.project IS UNIQUE;
DROP CONSTRAINT ON (r:Rule) ASSERT r.id, r.project IS UNIQUE;
DROP CONSTRAINT ON (t:Task) ASSERT t.id, t.project IS UNIQUE;
DROP CONSTRAINT ON (f:Finding) ASSERT f.id, f.project IS UNIQUE;
DROP CONSTRAINT ON (q:Question) ASSERT q.id, q.project IS UNIQUE;
DROP CONSTRAINT ON (risk:Risk) ASSERT risk.id, risk.project IS UNIQUE;
DROP CONSTRAINT ON (adr:ADR) ASSERT adr.id, adr.project IS UNIQUE;


// =====================================================
// DROP CODE CONSTRAINTS
// =====================================================

DROP CONSTRAINT ON (p:Project) ASSERT p.name IS UNIQUE;
DROP CONSTRAINT ON (c:Code) ASSERT c.project IS UNIQUE;

DROP CONSTRAINT ON (p:Package) ASSERT p.name, p.project IS UNIQUE;
DROP CONSTRAINT ON (c:Class) ASSERT c.fqn, c.project IS UNIQUE;
DROP CONSTRAINT ON (i:Interface) ASSERT i.fqn, i.project IS UNIQUE;
DROP CONSTRAINT ON (a:Annotation) ASSERT a.fqn, a.project IS UNIQUE;
DROP CONSTRAINT ON (m:Method) ASSERT m.signature, m.project IS UNIQUE;
DROP CONSTRAINT ON (f:Field) ASSERT f.fqn, f.project IS UNIQUE;
DROP CONSTRAINT ON (file:File) ASSERT file.path, file.project IS UNIQUE;


// =====================================================
// DROP MEMORY INDEXES
// =====================================================

DROP INDEX ON :Memory(project);

DROP INDEX ON :Decision(project);
DROP INDEX ON :Decision(status);
DROP INDEX ON :Decision(topic);
DROP INDEX ON :Decision(createdAt);

DROP INDEX ON :Idea(project);
DROP INDEX ON :Idea(status);
DROP INDEX ON :Idea(topic);

DROP INDEX ON :Context(project);
DROP INDEX ON :Context(topic);
DROP INDEX ON :Context(updatedAt);

DROP INDEX ON :Rule(project);
DROP INDEX ON :Rule(severity);
DROP INDEX ON :Rule(topic);

DROP INDEX ON :Task(project);
DROP INDEX ON :Task(status);
DROP INDEX ON :Task(priority);

DROP INDEX ON :Finding(project);
DROP INDEX ON :Finding(type);
DROP INDEX ON :Finding(topic);

DROP INDEX ON :Question(project);
DROP INDEX ON :Question(status);

DROP INDEX ON :Risk(project);
DROP INDEX ON :Risk(severity);
DROP INDEX ON :Risk(status);

DROP INDEX ON :ADR(project);
DROP INDEX ON :ADR(status);


// =====================================================
// DROP CODE INDEXES
// =====================================================

DROP INDEX ON :Project(name);
DROP INDEX ON :Code(project);

DROP INDEX ON :Class(project);
DROP INDEX ON :Class(name);

DROP INDEX ON :Interface(project);
DROP INDEX ON :Interface(name);

DROP INDEX ON :Annotation(project);
DROP INDEX ON :Annotation(name);

DROP INDEX ON :Method(project);
DROP INDEX ON :Method(name);

DROP INDEX ON :Field(project);
DROP INDEX ON :Field(name);

DROP INDEX ON :Class(packageName);