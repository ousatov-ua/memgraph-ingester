package io.github.ousatov.tools.memgraph.exe;

import com.github.javaparser.ast.Node;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.GraphWrite.AnnotationWrite;
import io.github.ousatov.tools.memgraph.exe.GraphWrite.CallWrite;
import io.github.ousatov.tools.memgraph.exe.GraphWrite.FieldWrite;
import io.github.ousatov.tools.memgraph.exe.GraphWrite.PendingCallWrite;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * Shared graph-writing primitives used by language-specific writers.
 *
 * <p>The language writers inherit these helpers so common Cypher and batching behavior stays in one
 * place while Java and JavaScript keep their parsing-specific logic separate.
 *
 * @author Oleksii Usatov
 */
public class CommonGraphWriter {

  protected static final String JAVA_LANGUAGE = SourceLanguage.JAVA.graphName();
  protected static final String JAVASCRIPT_LANGUAGE = SourceLanguage.JAVASCRIPT.graphName();

  private final CypherExecutor cypher;
  private final CallEdgeWriter callEdges;
  private final GraphNodeWriter nodes;

  protected CommonGraphWriter(Dependencies dependencies) {
    this.cypher = dependencies.cypher;
    this.callEdges = dependencies.callEdges;
    this.nodes = dependencies.nodes;
  }

  protected void run(String query, Map<String, Object> params) {
    cypher.run(query, params);
  }

  @SuppressWarnings("java:S107")
  protected void upsertClassNode(
      Path file,
      String pkg,
      String fqn,
      String name,
      boolean isAbstract,
      String visibility,
      boolean isEnum,
      boolean isRecord,
      boolean isFinal,
      String language,
      String kind,
      String modulePath,
      String framework) {
    cypher.run(
        Cypher.CYPHER_UPSERT_CLASS,
        Map.ofEntries(
            Map.entry(Params.FQN, fqn),
            Map.entry(Params.NAME, name),
            Map.entry(Params.PKG, pkg),
            Map.entry(Params.PATH, file.toString()),
            Map.entry(Params.IS_ABSTRACT, isAbstract),
            Map.entry(Params.VISIBILITY, visibility),
            Map.entry(Params.IS_ENUM, isEnum),
            Map.entry(Params.IS_RECORD, isRecord),
            Map.entry(Params.IS_FINAL, isFinal),
            Map.entry(Params.LANGUAGE, language),
            Map.entry(Params.KIND, kind),
            Map.entry(Params.MODULE_PATH, modulePath),
            Map.entry(Params.FRAMEWORK, framework)));
  }

  @SuppressWarnings("java:S107")
  protected void upsertClassNode(
      Path file,
      String pkg,
      String fqn,
      String name,
      boolean isAbstract,
      String visibility,
      boolean isEnum,
      boolean isRecord,
      boolean isFinal) {
    upsertClassNode(
        file,
        pkg,
        fqn,
        name,
        isAbstract,
        visibility,
        isEnum,
        isRecord,
        isFinal,
        JAVA_LANGUAGE,
        classKind(isEnum, isRecord),
        "",
        "");
  }

  @SuppressWarnings("java:S107")
  protected void upsertInterfaceNode(
      Path file,
      String pkg,
      String fqn,
      String name,
      boolean isAbstract,
      String visibility,
      String language,
      String kind,
      String modulePath,
      String framework) {
    cypher.run(
        Cypher.CYPHER_UPSERT_INTERFACE,
        Map.ofEntries(
            Map.entry(Params.FQN, fqn),
            Map.entry(Params.NAME, name),
            Map.entry(Params.PKG, pkg),
            Map.entry(Params.PATH, file.toString()),
            Map.entry(Params.IS_ABSTRACT, isAbstract),
            Map.entry(Params.VISIBILITY, visibility),
            Map.entry(Params.IS_FINAL, false),
            Map.entry(Params.LANGUAGE, language),
            Map.entry(Params.KIND, kind),
            Map.entry(Params.MODULE_PATH, modulePath),
            Map.entry(Params.FRAMEWORK, framework)));
  }

  protected void upsertInterfaceNode(
      Path file, String pkg, String fqn, String name, boolean isAbstract, String visibility) {
    upsertInterfaceNode(
        file, pkg, fqn, name, isAbstract, visibility, JAVA_LANGUAGE, "interface", "", "");
  }

  protected void upsertAnnotationNode(
      Path file, String pkg, String fqn, String name, String visibility) {
    cypher.run(
        Cypher.CYPHER_UPSERT_ANNOTATION,
        Map.of(
            Params.FQN,
            fqn,
            Params.NAME,
            name,
            Params.PKG,
            pkg,
            Params.PATH,
            file.toString(),
            Params.VISIBILITY,
            visibility,
            Params.LANGUAGE,
            JAVA_LANGUAGE,
            Params.KIND,
            Params.ANNOTATION,
            Params.MODULE_PATH,
            "",
            Params.FRAMEWORK,
            ""));
  }

  protected void upsertTypeRelation(
      String query,
      String childFqn,
      String targetFqn,
      String targetParam,
      String targetNameParam,
      String targetPkgParam,
      String language) {
    if (targetFqn == null || targetFqn.isBlank()) {
      return;
    }
    cypher.run(
        query,
        Map.of(
            Params.CHILD,
            childFqn,
            targetParam,
            targetFqn,
            targetNameParam,
            JavaTypeNames.nameFromFqn(targetFqn),
            targetPkgParam,
            JavaTypeNames.packageFromFqn(targetFqn),
            Params.LANGUAGE,
            language));
  }

  protected void upsertFieldNodes(Path file, Collection<FieldWrite> fields) {
    nodes.upsertFieldNodes(file, fields);
  }

  protected void upsertMethodNode(Path file, Method method) {
    nodes.upsertMethodNode(file, method);
  }

  protected void upsertMethodNodes(Path file, Collection<Method> methods) {
    nodes.upsertMethodNodes(file, methods);
  }

  protected void upsertAnnotationReferencesByFqn(Collection<AnnotationWrite> annotations) {
    nodes.upsertAnnotationReferencesByFqn(annotations);
  }

  protected void upsertAnnotationReferencesBySig(Collection<AnnotationWrite> annotations) {
    nodes.upsertAnnotationReferencesBySig(annotations);
  }

  protected void upsertCalls(Collection<CallWrite> calls) {
    nodes.upsertCalls(calls);
  }

  protected void upsertPendingCallsByName(Collection<PendingCallWrite> calls) {
    nodes.upsertPendingCallsByName(calls);
  }

  protected void upsertCallEdge(String callerSignature, String ownerFqn, Node bodyNode) {
    callEdges.upsert(callerSignature, ownerFqn, bodyNode);
  }

  private static String classKind(boolean isEnum, boolean isRecord) {
    if (isEnum) {
      return "enum";
    }
    if (isRecord) {
      return "record";
    }
    return "class";
  }

  /** Internal dependency bundle for language writers without exposing session primitives. */
  public static final class Dependencies {
    private final CypherExecutor cypher;
    private final CallEdgeWriter callEdges;
    private final GraphNodeWriter nodes;

    Dependencies(CypherExecutor cypher, CallEdgeWriter callEdges, GraphNodeWriter nodes) {
      this.cypher = cypher;
      this.callEdges = callEdges;
      this.nodes = nodes;
    }
  }
}
