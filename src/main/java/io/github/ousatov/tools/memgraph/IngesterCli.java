package io.github.ousatov.tools.memgraph;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Walks a Java source tree, parses each file with JavaParser, and writes a structural code graph
 * into Memgraph via the Bolt protocol.
 *
 * <p>All nodes are namespaced by {@code project} so multiple codebases can share a single Memgraph
 * instance. Every MERGE and MATCH scopes by project.
 */
@Command(name = "ingest", mixinStandardHelpOptions = true, version = "1.0")
public final class IngesterCli implements Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(IngesterCli.class);

  @Option(
      names = {"-s", "--source"},
      required = true,
      description = "Root source directory (e.g. src/main/java)")
  private Path sourceRoot;

  @Option(
      names = {"-b", "--bolt"},
      required = true,
      description = "Bolt URL, e.g. bolt://host:7687")
  private String boltUrl;

  @Option(
      names = {"-u", "--user"},
      defaultValue = "")
  private String user;

  @Option(
      names = {"-p", "--pass"},
      defaultValue = "")
  private String pass;

  @Option(
      names = {"-P", "--project"},
      required = true,
      description =
          "Logical project name; namespaces all nodes so multiple "
              + "projects can share one Memgraph instance")
  private String project;

  @Option(
      names = "--wipe",
      description = "Delete all nodes belonging to this project before ingesting")
  private boolean wipe;

  public static void main(String[] args) {
    int exit = new CommandLine(new IngesterCli()).execute(args);
    System.exit(exit);
  }

  /** Enables JavaParser to resolve types across the project and parse modern Java. */
  private static void configureSymbolSolver(Path sourceRoot) {
    CombinedTypeSolver solver = new CombinedTypeSolver();
    solver.add(new ReflectionTypeSolver());
    solver.add(new JavaParserTypeSolver(sourceRoot));

    ParserConfiguration config = StaticJavaParser.getParserConfiguration();
    config.setSymbolResolver(new JavaSymbolSolver(solver));
    config.setLanguageLevel(LanguageLevel.JAVA_25);
  }

  /**
   * Resolves a class/interface reference to its fully-qualified name. Returns empty when the type
   * cannot be resolved (e.g. generics, missing classpath entries).
   */
  private static Optional<String> resolveQualifiedName(ClassOrInterfaceType type) {
    try {
      ResolvedReferenceType resolved = type.resolve().asReferenceType();
      return resolved.getTypeDeclaration().map(td -> td.getQualifiedName());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static String buildSignature(String ownerFqn, MethodDeclaration m) {
    String params =
        m.getParameters().stream()
            .map(p -> p.getType().asString())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    return ownerFqn + "." + m.getNameAsString() + "(" + params + ")";
  }

  /** Swallows symbol-resolution failures — not every callee can be resolved. */
  private static void tryRun(Runnable action) {
    try {
      action.run();
    } catch (UnsolvedSymbolException | UnsupportedOperationException e) {
      // External libs or generics we can't resolve — skip silently.
    } catch (RuntimeException e) {
      log.debug("Skipping due to: {}", e.getMessage());
    }
  }

  @Override
  public Integer call() throws Exception {
    configureSymbolSolver(sourceRoot);

    try (Driver driver = GraphDatabase.driver(boltUrl, AuthTokens.basic(user, pass));
        Session session = driver.session()) {

      if (wipe) {
        log.info("Wiping existing graph for project '{}'...", project);
        session.run(
            "MATCH (n) WHERE n.project = $project DETACH DELETE n", Map.of("project", project));
        session.run(
            "MATCH (p:Project {name: $project}) SET p.sourceRoots = [] DETACH DELETE p",
            Map.of("project", project));
      }

      upsertProject(session);
      log.info("Upserted :Project anchor for '{}'", project);

      try (Stream<Path> files = Files.walk(sourceRoot)) {
        files.filter(p -> p.toString().endsWith(".java")).forEach(p -> ingestFile(session, p));
      }
    }
    log.info("Ingestion complete for project '{}'.", project);
    return 0;
  }

  /**
   * Creates or refreshes the {@code :Project} anchor node. This is the natural entry point for
   * Claude Code and other clients exploring the graph — they can discover projects via the schema
   * rather than having to guess at property values.
   */
  private void upsertProject(Session session) {
    // Append sourceRoot to sourceRoots list only if not already present.
    // coalesce() handles the first run where sourceRoots does not exist yet.
    session.run(
        """
        MERGE (proj:Project {name: $project})
          SET proj.sourceRoots  = CASE
                WHEN $sourceRoot IN coalesce(proj.sourceRoots, [])
                THEN coalesce(proj.sourceRoots, [])
                ELSE coalesce(proj.sourceRoots, []) + $sourceRoot
              END,
              proj.lastIngested = timestamp()
        """,
        Map.of("project", project, "sourceRoot", sourceRoot.toString()));
  }

  // ----------------------------------------------------------------
  // File-level ingestion
  // ----------------------------------------------------------------

  private void ingestFile(Session session, Path file) {
    CompilationUnit cu;
    try {
      cu = StaticJavaParser.parse(file);
    } catch (Exception e) {
      log.warn("Failed to parse {}: {}", file, e.getMessage());
      return;
    }

    String pkg = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");

    try {
      log.info("Ingesting {} (project={})", file, project);
      upsertFile(session, file);
      upsertPackage(session, pkg);

      cu.findAll(ClassOrInterfaceDeclaration.class)
          .forEach(decl -> ingestType(session, file, pkg, decl));
    } catch (Exception e) {
      log.warn("Failed to ingest {}: {}", file, e.getMessage());
    }
  }

  private void upsertFile(Session session, Path file) {
    session.run(
        """
        MERGE (f:File {path: $path, project: $project})
        WITH f
        MATCH (proj:Project {name: $project})
        MERGE (proj)-[:CONTAINS]->(f)
        """,
        Map.of("path", file.toString(), "project", project));
  }

  private void upsertPackage(Session session, String pkg) {
    session.run(
        """
        MERGE (p:Package {name: $name, project: $project})
        WITH p
        MATCH (proj:Project {name: $project})
        MERGE (proj)-[:CONTAINS]->(p)
        """,
        Map.of("name", pkg, "project", project));
  }

  // ----------------------------------------------------------------
  // Type-level ingestion
  // ----------------------------------------------------------------

  private void ingestType(
      Session session, Path file, String pkg, ClassOrInterfaceDeclaration decl) {
    String fqn = pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString();
    String label = decl.isInterface() ? "Interface" : "Class";

    session.run(
        """
        MERGE (t:%s {fqn: $fqn, project: $project})
          SET t.name = $name,
              t.packageName = $pkg,
              t.isAbstract = $isAbstract
        WITH t
        MATCH (p:Package {name: $pkg, project: $project})
        MERGE (p)-[:CONTAINS]->(t)
        WITH t
        MATCH (f:File {path: $path, project: $project})
        MERGE (f)-[:DEFINES]->(t)
        """
            .formatted(label),
        Map.of(
            "fqn",
            fqn,
            "name",
            decl.getNameAsString(),
            "pkg",
            pkg,
            "path",
            file.toString(),
            "isAbstract",
            decl.isAbstract(),
            "project",
            project));

    ingestInheritance(session, fqn, decl);
    decl.getFields().forEach(f -> ingestField(session, fqn, f));
    decl.getMethods().forEach(m -> ingestMethod(session, fqn, m));
  }

  private void ingestInheritance(Session session, String fqn, ClassOrInterfaceDeclaration decl) {
    decl.getExtendedTypes()
        .forEach(
            ext ->
                tryRun(
                    () ->
                        resolveQualifiedName(ext)
                            .ifPresent(
                                parent ->
                                    session.run(
                                        """
                                        MERGE (parent:Class {fqn: $parent, project: $project})
                                        WITH parent
                                        MATCH (child {fqn: $child, project: $project})
                                        MERGE (child)-[:EXTENDS]->(parent)
                                        """,
                                        Map.of(
                                            "child", fqn,
                                            "parent", parent,
                                            "project", project)))));

    decl.getImplementedTypes()
        .forEach(
            impl ->
                tryRun(
                    () ->
                        resolveQualifiedName(impl)
                            .ifPresent(
                                iface ->
                                    session.run(
                                        """
                                        MERGE (i:Interface {fqn: $iface, project: $project})
                                        WITH i
                                        MATCH (c:Class {fqn: $child, project: $project})
                                        MERGE (c)-[:IMPLEMENTS]->(i)
                                        """,
                                        Map.of(
                                            "child", fqn,
                                            "iface", iface,
                                            "project", project)))));
  }

  // ----------------------------------------------------------------
  // Members
  // ----------------------------------------------------------------

  private void ingestField(Session session, String ownerFqn, FieldDeclaration field) {
    field
        .getVariables()
        .forEach(
            v -> {
              String fqn = ownerFqn + "#" + v.getNameAsString();
              session.run(
                  """
                  MERGE (f:Field {fqn: $fqn, project: $project})
                    SET f.name = $name,
                        f.type = $type,
                        f.isStatic = $isStatic
                  WITH f
                  MATCH (owner {fqn: $owner, project: $project})
                  MERGE (owner)-[:DECLARES]->(f)
                  """,
                  Map.of(
                      "fqn",
                      fqn,
                      "name",
                      v.getNameAsString(),
                      "type",
                      v.getTypeAsString(),
                      "isStatic",
                      field.isStatic(),
                      "owner",
                      ownerFqn,
                      "project",
                      project));
            });
  }

  private void ingestMethod(Session session, String ownerFqn, MethodDeclaration method) {
    String signature = buildSignature(ownerFqn, method);

    session.run(
        """
        MERGE (m:Method {signature: $sig, project: $project})
          SET m.name = $name,
              m.returnType = $ret,
              m.isStatic = $isStatic,
              m.startLine = $start,
              m.endLine = $end
        WITH m
        MATCH (owner {fqn: $owner, project: $project})
        MERGE (owner)-[:DECLARES]->(m)
        """,
        Map.of(
            "sig", signature,
            "name", method.getNameAsString(),
            "ret", method.getTypeAsString(),
            "isStatic", method.isStatic(),
            "start", method.getBegin().map(p -> p.line).orElse(0),
            "end", method.getEnd().map(p -> p.line).orElse(0),
            "owner", ownerFqn,
            "project", project));

    ingestCalls(session, signature, method);
  }

  private void ingestCalls(Session session, String callerSig, MethodDeclaration method) {
    List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
    for (MethodCallExpr call : calls) {
      tryRun(
          () -> {
            ResolvedMethodDeclaration resolved = call.resolve();
            String calleeSig = resolved.getQualifiedSignature();
            session.run(
                """
                MERGE (callee:Method {signature: $callee, project: $project})
                WITH callee
                MATCH (caller:Method {signature: $caller, project: $project})
                MERGE (caller)-[:CALLS]->(callee)
                """,
                Map.of(
                    "caller", callerSig,
                    "callee", calleeSig,
                    "project", project));
          });
    }
  }
}
