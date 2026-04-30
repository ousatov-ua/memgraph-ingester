package io.github.ousatov.tools.memgraph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import io.github.ousatov.tools.memgraph.def.Const.Cypher;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes all Cypher upsert operations for a single Bolt session.
 *
 * <p>Each instance wraps exactly one {@link Session}. In sequential mode the orchestrator creates
 * one instance; in parallel mode each worker thread creates its own, ensuring no session is shared
 * across threads.
 *
 * @author Oleksii Usatov
 */
public final class GraphWriter {

  private static final Logger log = LoggerFactory.getLogger(GraphWriter.class);

  private static final int WIPE_BATCH_SIZE = 10_000;

  private final Session session;
  private final String project;

  /**
   * @param session Bolt session — must not be shared with other threads
   * @param project project name used to scope all Cypher operations
   */
  public GraphWriter(Session session, String project) {
    this.session = session;
    this.project = project;
  }

  static boolean isRetryable(RuntimeException e) {
    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    return msg.contains("conflicting transactions")
        || msg.contains("deadlock")
        || msg.contains("serializationerror")
        || msg.contains("unique constraint violation");
  }

  /**
   * Resolves a class/interface reference to its FQN. Returns empty for unresolvable types (e.g.
   * generics, missing classpath entries).
   */
  private static Optional<String> resolveQualifiedName(ClassOrInterfaceType type) {
    try {
      ResolvedReferenceType resolved = type.resolve().asReferenceType();
      return resolved.getTypeDeclaration().map(ResolvedTypeDeclaration::getQualifiedName);
    } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException _) {
      return Optional.empty();
    } catch (RuntimeException e) {
      throw new ProcessingException("Unexpected resolution failure", e);
    }
  }

  /**
   * Builds the method signature using symbol resolution when available, falling back to simple type
   * names. Both paths produce the same format as {@code call.resolve().getQualifiedSignature()}, so
   * CALLS edges connect correctly.
   */
  private static String buildSignature(String ownerFqn, MethodDeclaration m) {
    try {
      return m.resolve().getQualifiedSignature();
    } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException _) {
      String params =
          m.getParameters().stream()
              .map(p -> p.getType().asString())
              .collect(Collectors.joining(", "));
      return ownerFqn + "." + m.getNameAsString() + "(" + params + ")";
    }
  }

  /**
   * Builds the constructor signature using symbol resolution for FQN parameter types, falling back
   * to simple type names. Uses the {@code <init>} convention for consistent identification.
   */
  private static String buildConstructorSignature(String ownerFqn, ConstructorDeclaration ctor) {
    try {
      String resolved = ctor.resolve().getQualifiedSignature();
      // "com.example.Widget(java.lang.String, int)" → extract params
      int parenIdx = resolved.indexOf('(');
      String params = resolved.substring(parenIdx + 1, resolved.length() - 1);
      return ownerFqn + "." + Labels.INIT + "(" + params + ")";
    } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException _) {
      String params =
          ctor.getParameters().stream()
              .map(p -> p.getType().asString())
              .collect(Collectors.joining(", "));
      return ownerFqn + "." + Labels.INIT + "(" + params + ")";
    }
  }

  /** Swallows symbol-resolution failures — not every type/callee can be resolved. */
  private static void tryRun(Runnable action) {
    try {
      action.run();
    } catch (UnsolvedSymbolException | UnsupportedOperationException | IllegalStateException _) {
      // External libs, generics, or unconfigured resolver — skip silently.
    }
  }

  /** Constructs a fully qualified name from {@code pkg} and {@code simpleName}. */
  private static String buildFqn(String pkg, String simpleName) {
    return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
  }

  /** Deletes the project-scoped {@code :Code} graph in batches, keeping the {@code :Project}. */
  public void wipe() {
    Map<String, Object> params = Map.of("batchSize", WIPE_BATCH_SIZE, Labels.PROJECT, project);
    long deleted;
    do {
      deleted =
          session.executeWrite(
              tx ->
                  tx.run(Cypher.CYPHER_WIPE_PROJECT_CODE_BATCH, params)
                      .single()
                      .get("deleted")
                      .asLong());
    } while (deleted > 0);
  }

  /** Deletes the project-scoped {@code :Memory} graph while keeping the {@code :Project} anchor. */
  public void wipeMemories() {
    Map<String, Object> params = Map.of(Labels.PROJECT, project);
    session.executeWrite(
        tx -> {
          tx.run(Cypher.CYPHER_WIPE_PROJECT_MEMORIES, params).consume();
          return null;
        });
  }

  /** Refreshes {@code :CodeRef} resolution edges to the current project-scoped code graph. */
  public void resolveCodeRefs() {
    Map<String, Object> params = Map.of(Labels.PROJECT, project);
    session.executeWrite(
        tx -> {
          tx.run(Cypher.CYPHER_RESOLVE_CODE_REFS_CODE, params).consume();
          tx.run(Cypher.CYPHER_RESOLVE_CODE_REFS_PACKAGE, params).consume();
          tx.run(Cypher.CYPHER_RESOLVE_CODE_REFS_FILE, params).consume();
          tx.run(Cypher.CYPHER_RESOLVE_CODE_REFS_CLASS, params).consume();
          tx.run(Cypher.CYPHER_RESOLVE_CODE_REFS_INTERFACE, params).consume();
          tx.run(Cypher.CYPHER_RESOLVE_CODE_REFS_ANNOTATION, params).consume();
          tx.run(Cypher.CYPHER_RESOLVE_CODE_REFS_METHOD, params).consume();
          tx.run(Cypher.CYPHER_RESOLVE_CODE_REFS_FIELD, params).consume();
          return null;
        });
  }

  /**
   * Returns the stored {@code lastModified} epoch-millis for the given file path, or {@code -1} if
   * the file has no graph node.
   */
  public long getFileLastModified(Path file) {
    Map<String, Object> params = Map.of(Params.PATH, file.toString(), Labels.PROJECT, project);
    try {
      var result = session.run(Cypher.CYPHER_GET_FILE_LAST_MODIFIED, params);
      if (!result.hasNext()) {
        return -1L;
      }
      var value = result.single().get(Params.LAST_MODIFIED);
      return value.isNull() ? -1L : value.asLong();
    } catch (RuntimeException e) {
      log.debug("Could not fetch lastModified for {}: {}", file, e.getMessage());
      return -1L;
    }
  }

  /**
   * Batch-fetches stored {@code lastModified} values for all given paths in one query. Only files
   * already present in the graph are included in the returned map; absent files are omitted.
   *
   * @param files list of source paths to check
   * @return map of {@code path.toString()} → stored epoch-millis
   */
  public Map<String, Long> getAllFileLastModified(List<Path> files) {
    List<String> paths = files.stream().map(Path::toString).toList();
    Map<String, Object> params = Map.of("paths", paths, Labels.PROJECT, project);
    try {
      var result = session.run(Cypher.CYPHER_GET_FILES_LAST_MODIFIED, params);
      Map<String, Long> mtimes = HashMap.newHashMap(files.size() * 2);
      while (result.hasNext()) {
        var currentRec = result.next();
        String path = currentRec.get("path").asString(null);
        var value = currentRec.get(Params.LAST_MODIFIED);
        if (path != null && !value.isNull()) {
          mtimes.put(path, value.asLong());
        }
      }
      return mtimes;
    } catch (RuntimeException e) {
      log.debug("Could not batch-fetch lastModified values: {}", e.getMessage());
      return Collections.emptyMap();
    }
  }

  /** Creates or refreshes the {@code :Project -> :Code} and {@code :Project -> :Memory} anchors. */
  public void upsertProject(Path sourceRoot) {
    Map<String, Object> params =
        Map.of("sourceRoot", sourceRoot.toString(), Labels.PROJECT, project);
    session.executeWrite(
        tx -> {
          tx.run(Cypher.CYPHER_UPSERT_PROJECT, params).consume();
          return null;
        });
  }

  /** Upserts a {@code :File} node and links it to the code anchor. */
  public void upsertFile(TransactionContext tx, Path file) {
    long lastModified;
    try {
      lastModified = Files.getLastModifiedTime(file).toMillis();
    } catch (IOException _) {
      lastModified = -1L;
    }
    runInTx(
        tx,
        Cypher.CYPHER_UPSERT_FILE,
        Map.of(Params.PATH, file.toString(), Params.LAST_MODIFIED, lastModified));
  }

  /** Upserts a {@code :Package} node and links it to the code anchor. */
  public void upsertPackage(TransactionContext tx, String pkg) {
    runInTx(tx, Cypher.CYPHER_UPSERT_PACKAGE, Map.of(Params.NAME, pkg));
  }

  /**
   * Upserts a class or interface declaration and all of its members, including directly nested
   * types with their correct {@code $}-separated FQN.
   */
  public void upsertType(
      TransactionContext tx, Path file, String pkg, ClassOrInterfaceDeclaration decl) {
    upsertTypeInternal(tx, file, pkg, null, decl);
  }

  /**
   * Upserts an enum declaration as a {@code :Class} with {@code isEnum = true}, including its
   * fields, methods, constructors, implemented interfaces, and nested types.
   */
  public void upsertEnum(TransactionContext tx, Path file, String pkg, EnumDeclaration decl) {
    String fqn = buildFqn(pkg, decl.getNameAsString());
    runInTx(
        tx,
        Cypher.CYPHER_UPSERT_CLASS,
        Map.of(
            Params.FQN,
            fqn,
            Params.NAME,
            decl.getNameAsString(),
            Params.PKG,
            pkg,
            Params.PATH,
            file.toString(),
            Params.IS_ABSTRACT,
            false,
            Params.VISIBILITY,
            decl.getAccessSpecifier().asString(),
            Params.IS_ENUM,
            true,
            Params.IS_RECORD,
            false));
    upsertAnnotationsByFqn(tx, fqn, decl);
    upsertImplementedTypes(tx, fqn, decl);
    decl.getFields().forEach(f -> upsertField(tx, fqn, f));
    decl.getMethods().forEach(m -> upsertMethod(tx, fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(tx, fqn, c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeInternal(tx, file, pkg, fqn, nested));
  }

  /**
   * Upserts a record declaration as a {@code :Class} with {@code isRecord = true}, including its
   * fields, methods, constructors, implemented interfaces, and nested types.
   */
  public void upsertRecord(TransactionContext tx, Path file, String pkg, RecordDeclaration decl) {
    String fqn = buildFqn(pkg, decl.getNameAsString());
    runInTx(
        tx,
        Cypher.CYPHER_UPSERT_CLASS,
        Map.of(
            Params.FQN,
            fqn,
            Params.NAME,
            decl.getNameAsString(),
            Params.PKG,
            pkg,
            Params.PATH,
            file.toString(),
            Params.IS_ABSTRACT,
            false,
            Params.VISIBILITY,
            decl.getAccessSpecifier().asString(),
            Params.IS_ENUM,
            false,
            Params.IS_RECORD,
            true));
    upsertAnnotationsByFqn(tx, fqn, decl);
    upsertImplementedTypes(tx, fqn, decl);
    decl.getFields().forEach(f -> upsertField(tx, fqn, f));
    upsertRecordComponents(tx, fqn, decl);
    decl.getMethods().forEach(m -> upsertMethod(tx, fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(tx, fqn, c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeInternal(tx, file, pkg, fqn, nested));
  }

  /**
   * Upserts an {@code @interface} declaration as an {@code :Annotation} node, including {@code
   * ANNOTATED_WITH} edges for any meta-annotations applied to it.
   */
  public void upsertAnnotation(
      TransactionContext tx, Path file, String pkg, AnnotationDeclaration decl) {
    String fqn = buildFqn(pkg, decl.getNameAsString());
    runInTx(
        tx,
        Cypher.CYPHER_UPSERT_ANNOTATION,
        Map.of(
            Params.FQN,
            fqn,
            Params.NAME,
            decl.getNameAsString(),
            Params.PKG,
            pkg,
            Params.PATH,
            file.toString(),
            Params.VISIBILITY,
            decl.getAccessSpecifier().asString()));
    upsertAnnotationsByFqn(tx, fqn, decl);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including
   * directly nested types. Call this after all structural upserts for the file are complete so
   * every callee node already exists.
   */
  public void upsertTypeCallEdges(
      TransactionContext tx, String pkg, ClassOrInterfaceDeclaration decl) {
    upsertTypeCallEdgesInternal(tx, pkg, null, decl);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertEnumCallEdges(TransactionContext tx, String pkg, EnumDeclaration decl) {
    String fqn = buildFqn(pkg, decl.getNameAsString());
    decl.getMethods().forEach(m -> upsertCallEdges(tx, buildSignature(fqn, m), m));
    decl.getConstructors().forEach(c -> upsertCallEdges(tx, buildConstructorSignature(fqn, c), c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeCallEdgesInternal(tx, pkg, fqn, nested));
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertRecordCallEdges(TransactionContext tx, String pkg, RecordDeclaration decl) {
    String fqn = buildFqn(pkg, decl.getNameAsString());
    decl.getMethods().forEach(m -> upsertCallEdges(tx, buildSignature(fqn, m), m));
    decl.getConstructors().forEach(c -> upsertCallEdges(tx, buildConstructorSignature(fqn, c), c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeCallEdgesInternal(tx, pkg, fqn, nested));
  }

  /**
   * Wraps the given work in a single {@code session.executeWrite()} transaction. All upsert methods
   * accepting a {@link TransactionContext} should be called within this wrapper to ensure atomicity
   * and leverage the driver's built-in retry on transient errors.
   *
   * @param work consumer receiving the active {@link TransactionContext}
   */
  public void executeWrite(Consumer<TransactionContext> work) {
    session.executeWrite(
        tx -> {
          work.accept(tx);
          return null;
        });
  }

  private void upsertTypeCallEdgesInternal(
      TransactionContext tx, String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn =
        outerFqn != null ? outerFqn + "$" + decl.getNameAsString() : genDeclName(pkg, decl);
    decl.getMethods().forEach(m -> upsertCallEdges(tx, buildSignature(fqn, m), m));
    decl.getConstructors().forEach(c -> upsertCallEdges(tx, buildConstructorSignature(fqn, c), c));
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeCallEdgesInternal(tx, pkg, fqn, nested));
  }

  private void upsertTypeInternal(
      TransactionContext tx,
      Path file,
      String pkg,
      String outerFqn,
      ClassOrInterfaceDeclaration decl) {
    String fqn =
        outerFqn != null ? outerFqn + "$" + decl.getNameAsString() : genDeclName(pkg, decl);
    if (decl.isInterface()) {
      runInTx(
          tx,
          Cypher.CYPHER_UPSERT_INTERFACE,
          Map.of(
              Params.FQN,
              fqn,
              Params.NAME,
              decl.getNameAsString(),
              Params.PKG,
              pkg,
              Params.PATH,
              file.toString(),
              Params.IS_ABSTRACT,
              decl.isAbstract(),
              Params.VISIBILITY,
              decl.getAccessSpecifier().asString()));
    } else {
      runInTx(
          tx,
          Cypher.CYPHER_UPSERT_CLASS,
          Map.of(
              Params.FQN,
              fqn,
              Params.NAME,
              decl.getNameAsString(),
              Params.PKG,
              pkg,
              Params.PATH,
              file.toString(),
              Params.IS_ABSTRACT,
              decl.isAbstract(),
              Params.VISIBILITY,
              decl.getAccessSpecifier().asString(),
              Params.IS_ENUM,
              false,
              Params.IS_RECORD,
              false));
    }
    upsertAnnotationsByFqn(tx, fqn, decl);
    upsertInheritance(tx, fqn, decl);
    decl.getFields().forEach(f -> upsertField(tx, fqn, f));
    decl.getMethods().forEach(m -> upsertMethod(tx, fqn, m));
    decl.getConstructors().forEach(c -> upsertConstructor(tx, fqn, c));
    // Recurse into directly nested class/interface declarations with correct FQN.
    decl.getMembers().stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(m -> (ClassOrInterfaceDeclaration) m)
        .forEach(nested -> upsertTypeInternal(tx, file, pkg, fqn, nested));
  }

  private String genDeclName(String pkg, ClassOrInterfaceDeclaration decl) {
    return buildFqn(pkg, decl.getNameAsString());
  }

  private void upsertInheritance(
      TransactionContext tx, String fqn, ClassOrInterfaceDeclaration decl) {
    String extendsCypher =
        decl.isInterface()
            ? Cypher.CYPHER_UPSERT_INTERFACE_EXTENDS
            : Cypher.CYPHER_UPSERT_EXTENDS_CLASS;
    decl.getExtendedTypes()
        .forEach(
            ext ->
                withResolvedType(
                    ext,
                    parent ->
                        runInTx(
                            tx, extendsCypher, Map.of(Params.CHILD, fqn, Params.PARENT, parent))));
    decl.getImplementedTypes()
        .forEach(
            impl ->
                withResolvedType(
                    impl,
                    iface ->
                        runInTx(
                            tx,
                            Cypher.CYPHER_UPSERT_IMPLEMENTS,
                            Map.of(Params.CHILD, fqn, Params.IFACE, iface))));
  }

  /** Writes {@code IMPLEMENTS} edges for enums and records that implement interfaces. */
  private void upsertImplementedTypes(
      TransactionContext tx, String fqn, NodeWithImplements<?> decl) {
    decl.getImplementedTypes()
        .forEach(
            impl ->
                withResolvedType(
                    impl,
                    iface ->
                        runInTx(
                            tx,
                            Cypher.CYPHER_UPSERT_IMPLEMENTS,
                            Map.of(Params.CHILD, fqn, Params.IFACE, iface))));
  }

  /** Upserts record components (parameters) as {@code :Field} nodes. */
  private void upsertRecordComponents(
      TransactionContext tx, String ownerFqn, RecordDeclaration decl) {
    for (Parameter param : decl.getParameters()) {
      String fqn = ownerFqn + "#" + param.getNameAsString();
      runInTx(
          tx,
          Cypher.CYPHER_UPSERT_FIELD,
          Map.of(
              Params.FQN,
              fqn,
              Params.NAME,
              param.getNameAsString(),
              "type",
              param.getTypeAsString(),
              Params.IS_STATIC,
              false,
              Params.VISIBILITY,
              "private",
              Params.OWNER,
              ownerFqn));
      upsertAnnotationsByFqn(tx, fqn, param);
    }
  }

  private void upsertField(TransactionContext tx, String ownerFqn, FieldDeclaration field) {
    field
        .getVariables()
        .forEach(
            v -> {
              String fqn = ownerFqn + "#" + v.getNameAsString();
              runInTx(
                  tx,
                  Cypher.CYPHER_UPSERT_FIELD,
                  Map.of(
                      Params.FQN,
                      fqn,
                      Params.NAME,
                      v.getNameAsString(),
                      "type",
                      v.getTypeAsString(),
                      Params.IS_STATIC,
                      field.isStatic(),
                      Params.VISIBILITY,
                      field.getAccessSpecifier().asString(),
                      Params.OWNER,
                      ownerFqn));
              upsertAnnotationsByFqn(tx, fqn, field);
            });
  }

  private void upsertMethod(TransactionContext tx, String ownerFqn, MethodDeclaration method) {
    String signature = buildSignature(ownerFqn, method);
    runInTx(
        tx,
        Cypher.CYPHER_UPSERT_METHOD,
        Map.of(
            Params.SIG,
            signature,
            Params.NAME,
            method.getNameAsString(),
            Params.RET,
            method.getTypeAsString(),
            Params.IS_STATIC,
            method.isStatic(),
            Params.VISIBILITY,
            method.getAccessSpecifier().asString(),
            Params.START,
            method.getBegin().map(p -> p.line).orElse(0),
            Params.END,
            method.getEnd().map(p -> p.line).orElse(0),
            Params.OWNER,
            ownerFqn));
    upsertAnnotationsBySig(tx, signature, method);
  }

  private void upsertConstructor(
      TransactionContext tx, String ownerFqn, ConstructorDeclaration ctor) {
    String signature = buildConstructorSignature(ownerFqn, ctor);
    runInTx(
        tx,
        Cypher.CYPHER_UPSERT_METHOD,
        Map.of(
            Params.SIG,
            signature,
            Params.NAME,
            Labels.INIT,
            Params.RET,
            Labels.VOID,
            Params.IS_STATIC,
            false,
            Params.VISIBILITY,
            ctor.getAccessSpecifier().asString(),
            Params.START,
            ctor.getBegin().map(p -> p.line).orElse(0),
            Params.END,
            ctor.getEnd().map(p -> p.line).orElse(0),
            Params.OWNER,
            ownerFqn));
    upsertAnnotationsBySig(tx, signature, ctor);
  }

  /**
   * Finds all method call expressions inside {@code bodyNode}, resolves each callee, and writes a
   * {@code CALLS} edge. Replaces the former {@code upsertCalls} and {@code upsertConstructorCalls}
   * pair.
   */
  private void upsertCallEdges(TransactionContext tx, String callerSig, Node bodyNode) {
    bodyNode
        .findAll(MethodCallExpr.class)
        .forEach(
            call ->
                tryRun(
                    () -> {
                      ResolvedMethodDeclaration resolved = call.resolve();
                      String calleeSig = resolved.getQualifiedSignature();
                      runInTx(
                          tx,
                          Cypher.CYPHER_UPSERT_CALL,
                          Map.of(Params.CALLER, callerSig, Params.CALLEE, calleeSig));
                    }));
  }

  /**
   * Resolves each annotation on {@code node} and writes an {@code ANNOTATED_WITH} edge from the
   * element identified by {@code ownerFqn}. Falls back to the simple annotation name when the
   * symbol resolver cannot determine the FQN.
   */
  private void upsertAnnotationsByFqn(
      TransactionContext tx, String ownerFqn, NodeWithAnnotations<?> node) {
    upsertAnnotations(tx, Params.OWNER, ownerFqn, node, Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_FQN);
  }

  /**
   * Resolves each annotation on {@code node} and writes an {@code ANNOTATED_WITH} edge from the
   * method identified by {@code sig}. Falls back to the simple annotation name when the symbol
   * resolver cannot determine the FQN.
   */
  private void upsertAnnotationsBySig(
      TransactionContext tx, String sig, NodeWithAnnotations<?> node) {
    upsertAnnotations(tx, Params.SIG, sig, node, Cypher.CYPHER_UPSERT_ANNOTATED_WITH_BY_SIG);
  }

  private void upsertAnnotations(
      TransactionContext tx,
      String paramKey,
      String paramValue,
      NodeWithAnnotations<?> node,
      String cypher) {
    node.getAnnotations()
        .forEach(
            ann -> {
              String annotFqn;
              try {
                annotFqn = ann.resolve().getQualifiedName();
              } catch (UnsolvedSymbolException
                  | UnsupportedOperationException
                  | IllegalStateException _) {
                annotFqn = ann.getNameAsString();
              }
              runInTx(tx, cypher, Map.of(paramKey, paramValue, Params.ANNOT_FQN, annotFqn));
            });
  }

  /** Resolves {@code type} and invokes {@code action} with the FQN; silently skips on failure. */
  private void withResolvedType(ClassOrInterfaceType type, Consumer<String> action) {
    tryRun(() -> resolveQualifiedName(type).ifPresent(action));
  }

  /**
   * Runs {@code cypher} within the given transaction. Auto-injects the {@code project} parameter so
   * callers do not need to include it in {@code params}.
   */
  private void runInTx(TransactionContext tx, String cypher, Map<String, Object> params) {
    Map<String, Object> allParams = new HashMap<>(params);
    allParams.put(Labels.PROJECT, project);
    tx.run(cypher, allParams).consume();
  }
}
