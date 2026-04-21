package io.github.ousatov.tools.memgraph.def;

/**
 * Const
 *
 * @author Oleksii Usatov
 * @since 20.04.2026
 */
public class Const {

  private Const() {

    // Empty
  }

  public static class Cypher {

    /** Upserts an {@code @interface} declaration as an {@code :Annotation} node. */
    public static final String CYPHER_UPSERT_ANNOTATION =
        """
        MERGE (a:Annotation {fqn: $fqn, project: $project})
          SET a.name = $name,
              a.packageName = $pkg,
              a.visibility = $visibility
        WITH a
        MATCH (p:Package {name: $pkg, project: $project})
        MERGE (p)-[:CONTAINS]->(a)
        WITH a
        MATCH (f:File {path: $path, project: $project})
        MERGE (f)-[:DEFINES]->(a)
        """;

    /**
     * Merges an {@code [:ANNOTATED_WITH]} edge from an element identified by {@code fqn} (Class,
     * Interface, Annotation, or Field) to an {@code :Annotation} node.
     */
    public static final String CYPHER_UPSERT_ANNOTATED_WITH_BY_FQN =
        """
        MERGE (a:Annotation {fqn: $annotFqn, project: $project})
        WITH a
        MATCH (owner {fqn: $owner, project: $project})
        MERGE (owner)-[:ANNOTATED_WITH]->(a)
        """;

    /**
     * Merges an {@code [:ANNOTATED_WITH]} edge from a {@code :Method} identified by {@code
     * signature} to an {@code :Annotation} node.
     */
    public static final String CYPHER_UPSERT_ANNOTATED_WITH_BY_SIG =
        """
        MERGE (a:Annotation {fqn: $annotFqn, project: $project})
        WITH a
        MATCH (m:Method {signature: $sig, project: $project})
        MERGE (m)-[:ANNOTATED_WITH]->(a)
        """;

    public static final String CYPHER_WIPE_NODES =
        "MATCH (n) WHERE n.project = $project DETACH DELETE n";
    public static final String CYPHER_WIPE_PROJECT =
        "MATCH (p:Project {name: $project}) DETACH DELETE p";
    public static final String CYPHER_UPSERT_PROJECT =
        """
        MERGE (proj:Project {name: $project})
          SET proj.sourceRoots  = CASE
                WHEN $sourceRoot IN coalesce(proj.sourceRoots, [])
                THEN coalesce(proj.sourceRoots, [])
                ELSE coalesce(proj.sourceRoots, []) + $sourceRoot
              END,
              proj.lastIngested = timestamp()
        """;
    public static final String CYPHER_UPSERT_FILE =
        """
        MERGE (f:File {path: $path, project: $project})
          SET f.lastModified = $lastModified
        WITH f
        MATCH (proj:Project {name: $project})
        MERGE (proj)-[:CONTAINS]->(f)
        """;
    public static final String CYPHER_UPSERT_PACKAGE =
        """
        MERGE (p:Package {name: $name, project: $project})
        WITH p
        MATCH (proj:Project {name: $project})
        MERGE (proj)-[:CONTAINS]->(p)
        """;

    /**
     * Template for class/interface upsert — {@code %s} is replaced with {@code Class} or {@code
     * Interface} at call time.
     */
    public static final String CYPHER_UPSERT_TYPE_TEMPLATE =
        """
        MERGE (t:%s {fqn: $fqn, project: $project})
          SET t.name = $name,
              t.packageName = $pkg,
              t.isAbstract = $isAbstract,
              t.visibility = $visibility,
              t.isEnum = $isEnum,
              t.isRecord = $isRecord
        WITH t
        MATCH (p:Package {name: $pkg, project: $project})
        MERGE (p)-[:CONTAINS]->(t)
        WITH t
        MATCH (f:File {path: $path, project: $project})
        MERGE (f)-[:DEFINES]->(t)
        """;

    public static final String CYPHER_UPSERT_EXTENDS =
        """
        MERGE (parent:Class {fqn: $parent, project: $project})
        WITH parent
        MATCH (child {fqn: $child, project: $project})
        MERGE (child)-[:EXTENDS]->(parent)
        """;

    /** Used when an interface extends another interface — parent must be {@code :Interface}. */
    public static final String CYPHER_UPSERT_INTERFACE_EXTENDS =
        """
        MERGE (parent:Interface {fqn: $parent, project: $project})
        WITH parent
        MATCH (child {fqn: $child, project: $project})
        MERGE (child)-[:EXTENDS]->(parent)
        """;

    public static final String CYPHER_UPSERT_IMPLEMENTS =
        """
        MERGE (i:Interface {fqn: $iface, project: $project})
        WITH i
        MATCH (c:Class {fqn: $child, project: $project})
        MERGE (c)-[:IMPLEMENTS]->(i)
        """;
    public static final String CYPHER_UPSERT_FIELD =
        """
        MERGE (f:Field {fqn: $fqn, project: $project})
          SET f.name = $name,
              f.type = $type,
              f.isStatic = $isStatic,
              f.visibility = $visibility
        WITH f
        MATCH (owner {fqn: $owner, project: $project})
        MERGE (owner)-[:DECLARES]->(f)
        """;
    public static final String CYPHER_UPSERT_METHOD =
        """
        MERGE (m:Method {signature: $sig, project: $project})
          SET m.name = $name,
              m.returnType = $ret,
              m.isStatic = $isStatic,
              m.visibility = $visibility,
              m.startLine = $start,
              m.endLine = $end
        WITH m
        MATCH (owner {fqn: $owner, project: $project})
        MERGE (owner)-[:DECLARES]->(m)
        """;

    /**
     * Callee is matched (not merged) so external library methods are never created as
     * project-scoped phantom nodes. Cross-file in-project calls missed on the first pass are filled
     * in by a subsequent wipe-less re-ingestion.
     */
    public static final String CYPHER_UPSERT_CALL =
        """
        MATCH (caller:Method {signature: $caller, project: $project})
        MATCH (callee:Method {signature: $callee, project: $project})
        MERGE (caller)-[:CALLS]->(callee)
        """;

    private Cypher() {

      // Empty
    }
  }

  public static class Labels {

    public static final String CALLER = "caller";
    public static final String CALLEE = "callee";
    public static final String FQN = "fqn";
    public static final String NAME = "name";
    public static final String CLASS = "Class";
    public static final String ANNOTATION = "Annotation";
    public static final String ANNOT_FQN = "annotFqn";
    public static final String PKG = "pkg";
    public static final String PATH = "path";
    public static final String IS_ABSTRACT = "isAbstract";
    public static final String VISIBILITY = "visibility";
    public static final String IS_ENUM = "isEnum";
    public static final String IS_RECORD = "isRecord";
    public static final String OWNER = "owner";
    public static final String CHILD = "child";
    public static final String PARENT = "parent";
    public static final String IFACE = "iface";
    public static final String SIG = "sig";
    public static final String IS_STATIC = "isStatic";
    public static final String RET = "ret";
    public static final String START = "start";
    public static final String END = "end";
    public static final String INIT = "<init>";
    public static final String VOID = "void";
    public static final String INTERFACE = "Interface";

    private Labels() {

      // Empty
    }
  }
}
