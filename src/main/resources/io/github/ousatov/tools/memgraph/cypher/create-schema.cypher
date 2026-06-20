CREATE CONSTRAINT ON (p:Project)    ASSERT p.name                   IS UNIQUE;

CREATE CONSTRAINT ON (l:Language)   ASSERT l.project, l.name        IS UNIQUE;
CREATE CONSTRAINT ON (c:Code)       ASSERT c.project, c.language    IS UNIQUE;

CREATE CONSTRAINT ON (p:Package)    ASSERT p.name, p.project, p.language IS UNIQUE;
CREATE CONSTRAINT ON (c:Class)      ASSERT c.fqn, c.project         IS UNIQUE;
CREATE CONSTRAINT ON (i:Interface)  ASSERT i.fqn, i.project         IS UNIQUE;
CREATE CONSTRAINT ON (a:Annotation) ASSERT a.fqn, a.project         IS UNIQUE;
CREATE CONSTRAINT ON (m:Method)     ASSERT m.signature, m.project   IS UNIQUE;
CREATE CONSTRAINT ON (f:Field)      ASSERT f.fqn, f.project         IS UNIQUE;
CREATE CONSTRAINT ON (file:File)    ASSERT file.path, file.project  IS UNIQUE;
CREATE CONSTRAINT ON (pc:PendingCall) ASSERT pc.project, pc.callerSignature, pc.calleeOwnerFqn, pc.calleeName IS UNIQUE;
CREATE INDEX ON :Project(name);
CREATE INDEX ON :Code(project);
CREATE INDEX ON :Code(language);
CREATE INDEX ON :Language(project);
CREATE INDEX ON :Language(name);
CREATE INDEX ON :File(project);
CREATE INDEX ON :File(path);
CREATE INDEX ON :File(language);
CREATE INDEX ON :File(project, language, path);
CREATE INDEX ON :Package(project);
CREATE INDEX ON :Package(name);
CREATE INDEX ON :Class(project);
CREATE INDEX ON :Class(fqn);
CREATE INDEX ON :Class(name);
CREATE INDEX ON :Interface(project);
CREATE INDEX ON :Interface(fqn);
CREATE INDEX ON :Interface(name);
CREATE INDEX ON :Annotation(project);
CREATE INDEX ON :Annotation(fqn);
CREATE INDEX ON :Annotation(name);
CREATE INDEX ON :Method(project);
CREATE INDEX ON :Method(signature);
CREATE INDEX ON :Method(name);
CREATE INDEX ON :Method(ownerFqn);
CREATE INDEX ON :Field(project);
CREATE INDEX ON :Field(fqn);
CREATE INDEX ON :Field(name);
CREATE INDEX ON :Class(packageName);
CREATE INDEX ON :Package(language);
CREATE INDEX ON :PendingCall(project);
CREATE INDEX ON :PendingCall(callerSignature);
CREATE INDEX ON :PendingCall(calleeOwnerFqn);
CREATE INDEX ON :PendingCall(calleeName);

CREATE CONSTRAINT ON (m:Memory)    ASSERT m.project IS UNIQUE;

CREATE CONSTRAINT ON (d:Decision)  ASSERT d.id, d.project IS UNIQUE;
CREATE CONSTRAINT ON (i:Idea)      ASSERT i.id, i.project IS UNIQUE;
CREATE CONSTRAINT ON (c:Context)   ASSERT c.id, c.project IS UNIQUE;
CREATE CONSTRAINT ON (r:Rule)      ASSERT r.id, r.project IS UNIQUE;
CREATE CONSTRAINT ON (t:Task)      ASSERT t.id, t.project IS UNIQUE;
CREATE CONSTRAINT ON (f:Finding)   ASSERT f.id, f.project IS UNIQUE;
CREATE CONSTRAINT ON (q:Question)  ASSERT q.id, q.project IS UNIQUE;
CREATE CONSTRAINT ON (risk:Risk)   ASSERT risk.id, risk.project IS UNIQUE;
CREATE CONSTRAINT ON (adr:ADR)     ASSERT adr.id, adr.project IS UNIQUE;
CREATE CONSTRAINT ON (ref:CodeRef) ASSERT ref.project, ref.targetType, ref.key IS UNIQUE;
CREATE CONSTRAINT ON (mc:MemoryChunk) ASSERT mc.id, mc.project IS UNIQUE;
CREATE CONSTRAINT ON (cc:CodeChunk)   ASSERT cc.id, cc.project IS UNIQUE;

CREATE INDEX ON :Memory(project);
CREATE INDEX ON :CodeRef(project);
CREATE INDEX ON :CodeRef(targetType);
CREATE INDEX ON :CodeRef(key);

CREATE INDEX ON :Decision(project);
CREATE INDEX ON :Decision(status);
CREATE INDEX ON :Decision(topic);
CREATE INDEX ON :Decision(createdAt);

CREATE INDEX ON :Idea(project);
CREATE INDEX ON :Idea(status);
CREATE INDEX ON :Idea(topic);

CREATE INDEX ON :Context(project);
CREATE INDEX ON :Context(topic);
CREATE INDEX ON :Context(updatedAt);

CREATE INDEX ON :Rule(project);
CREATE INDEX ON :Rule(severity);
CREATE INDEX ON :Rule(topic);

CREATE INDEX ON :Task(project);
CREATE INDEX ON :Task(status);
CREATE INDEX ON :Task(priority);

CREATE INDEX ON :Finding(project);
CREATE INDEX ON :Finding(type);
CREATE INDEX ON :Finding(topic);

CREATE INDEX ON :Question(project);
CREATE INDEX ON :Question(status);

CREATE INDEX ON :Risk(project);
CREATE INDEX ON :Risk(severity);
CREATE INDEX ON :Risk(status);

CREATE INDEX ON :ADR(project);
CREATE INDEX ON :ADR(status);

CREATE INDEX ON :MemoryChunk(project);
CREATE INDEX ON :MemoryChunk(id);
CREATE INDEX ON :MemoryChunk(sourceLabel);
CREATE INDEX ON :MemoryChunk(sourceId);
CREATE INDEX ON :MemoryChunk(textHash);
CREATE INDEX ON :MemoryChunk(embeddingModel);
CREATE INDEX ON :MemoryChunk(embeddingDimensions);
CREATE INDEX ON :MemoryChunk(embeddingDirty);
CREATE INDEX ON :MemoryChunk(project, embeddingDirty);

CREATE INDEX ON :CodeChunk(project);
CREATE INDEX ON :CodeChunk(id);
CREATE INDEX ON :CodeChunk(sourceLabel);
CREATE INDEX ON :CodeChunk(sourceId);
CREATE INDEX ON :CodeChunk(textHash);
CREATE INDEX ON :CodeChunk(embeddingModel);
CREATE INDEX ON :CodeChunk(embeddingDimensions);
CREATE INDEX ON :CodeChunk(embeddingDirty);
CREATE INDEX ON :CodeChunk(project, embeddingDirty);
CREATE INDEX ON :CodeChunk(language);
CREATE INDEX ON :CodeChunk(path);
CREATE INDEX ON :CodeChunk(ownerFqn);
CREATE INDEX ON :CodeChunk(signature);
