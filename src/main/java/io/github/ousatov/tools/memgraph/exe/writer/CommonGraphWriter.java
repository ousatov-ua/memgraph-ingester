package io.github.ousatov.tools.memgraph.exe.writer;

import com.github.javaparser.ast.Node;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.vo.Method;
import io.github.ousatov.tools.memgraph.vo.writer.AnnotationNodeWrite;
import io.github.ousatov.tools.memgraph.vo.writer.AnnotationWrite;
import io.github.ousatov.tools.memgraph.vo.writer.CallWrite;
import io.github.ousatov.tools.memgraph.vo.writer.ClassWrite;
import io.github.ousatov.tools.memgraph.vo.writer.FieldWrite;
import io.github.ousatov.tools.memgraph.vo.writer.InterfaceWrite;
import io.github.ousatov.tools.memgraph.vo.writer.PendingCallWrite;
import io.github.ousatov.tools.memgraph.vo.writer.TypeRelationWrite;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
  protected static final String PYTHON_LANGUAGE = SourceLanguage.PYTHON.graphName();

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

  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
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
    upsertClassNodes(
        List.of(
            new ClassWrite(
                file,
                pkg,
                fqn,
                name,
                isAbstract,
                visibility,
                isEnum,
                isRecord,
                isFinal,
                language,
                kind,
                modulePath,
                framework)));
  }

  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
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
        Const.Symbols.EMPTY,
        Const.Symbols.EMPTY);
  }

  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
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
    upsertInterfaceNodes(
        List.of(
            new InterfaceWrite(
                file,
                pkg,
                fqn,
                name,
                isAbstract,
                visibility,
                false,
                language,
                kind,
                modulePath,
                framework)));
  }

  protected void upsertInterfaceNode(
      Path file, String pkg, String fqn, String name, boolean isAbstract, String visibility) {
    upsertInterfaceNode(
        file,
        pkg,
        fqn,
        name,
        isAbstract,
        visibility,
        JAVA_LANGUAGE,
        Params.INTERFACE,
        Const.Symbols.EMPTY,
        Const.Symbols.EMPTY);
  }

  protected void upsertAnnotationNode(
      Path file, String pkg, String fqn, String name, String visibility) {
    upsertAnnotationNodes(
        List.of(
            new AnnotationNodeWrite(
                file,
                pkg,
                fqn,
                name,
                visibility,
                JAVA_LANGUAGE,
                Params.ANNOTATION,
                Const.Symbols.EMPTY,
                Const.Symbols.EMPTY)));
  }

  protected void upsertClassNodes(Collection<ClassWrite> classes) {
    nodes.upsertClassNodes(classes);
  }

  protected void upsertInterfaceNodes(Collection<InterfaceWrite> interfaces) {
    nodes.upsertInterfaceNodes(interfaces);
  }

  protected void upsertAnnotationNodes(Collection<AnnotationNodeWrite> annotations) {
    nodes.upsertAnnotationNodes(annotations);
  }

  protected void upsertClassExtends(Collection<TypeRelationWrite> relations) {
    nodes.upsertClassExtends(relations);
  }

  protected void upsertInterfaceExtends(Collection<TypeRelationWrite> relations) {
    nodes.upsertInterfaceExtends(relations);
  }

  protected void upsertImplements(Collection<TypeRelationWrite> relations) {
    nodes.upsertImplements(relations);
  }

  protected void upsertClassExtends(String childFqn, String parentFqn, String language) {
    upsertTypeRelation(
        parentFqn, relation -> upsertClassExtends(List.of(relation)), childFqn, language);
  }

  protected void upsertInterfaceExtends(String childFqn, String parentFqn, String language) {
    upsertTypeRelation(
        parentFqn, relation -> upsertInterfaceExtends(List.of(relation)), childFqn, language);
  }

  protected void upsertImplements(String childFqn, String interfaceFqn, String language) {
    upsertTypeRelation(
        interfaceFqn, relation -> upsertImplements(List.of(relation)), childFqn, language);
  }

  private static void upsertTypeRelation(
      String targetFqn, Consumer<TypeRelationWrite> upsert, String childFqn, String language) {
    if (targetFqn == null || targetFqn.isBlank()) {
      return;
    }
    upsert.accept(new TypeRelationWrite(childFqn, targetFqn, language));
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

  protected void upsertCallsByName(Collection<PendingCallWrite> calls) {
    nodes.upsertCallsByName(calls);
  }

  protected void upsertCallEdge(String callerSignature, String ownerFqn, Node bodyNode) {
    callEdges.upsert(callerSignature, ownerFqn, bodyNode);
  }

  protected void collectCallEdges(
      List<CallWrite> resolvedCalls,
      List<PendingCallWrite> pendingCalls,
      String callerSignature,
      String ownerFqn,
      Node bodyNode) {
    callEdges.collect(resolvedCalls, pendingCalls, callerSignature, ownerFqn, bodyNode);
  }

  private static String classKind(boolean isEnum, boolean isRecord) {
    if (isEnum) {
      return Params.ENUM;
    }
    if (isRecord) {
      return Params.RECORD;
    }
    return Params.CLASS;
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
