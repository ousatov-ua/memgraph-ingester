package com.olus.tools.memgraph;

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
 * Walks a Java source tree, parses each file with JavaParser, and writes
 * a structural code graph into Memgraph via the Bolt protocol.
 *
 * Usage:
 *   java -jar memgraph-ingester.jar \
 *        --source src/main/java \
 *        --bolt   bolt://memgraph.example:7687 \
 *        --user   memgraph \
 *        --pass   secret
 *
 * @author Oleksii Usatov
 */
@Command(name = "ingest", mixinStandardHelpOptions = true, version = "1.0")
public final class IngesterCli implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(IngesterCli.class);

    @Option(names = {"-s", "--source"}, required = true,
        description = "Root source directory (e.g. src/main/java)")
    private Path sourceRoot;

    @Option(names = {"-b", "--bolt"}, required = true,
        description = "Bolt URL, e.g. bolt://host:7687")
    private String boltUrl;

    @Option(names = {"-u", "--user"}, defaultValue = "")
    private String user;

    @Option(names = {"-p", "--pass"}, defaultValue = "")
    private String pass;

    @Option(names = "--wipe", description = "Delete all nodes before ingesting")
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
     * Resolves a class/interface reference to its fully-qualified name.
     * Returns empty when the type cannot be resolved (e.g. generics,
     * missing classpath entries).
     */
    private static Optional<String> resolveQualifiedName(ClassOrInterfaceType type) {
        try {
            ResolvedReferenceType resolved = type.resolve().asReferenceType();
            return resolved.getTypeDeclaration().map(td -> td.getQualifiedName());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ----------------------------------------------------------------
    // File-level ingestion
    // ----------------------------------------------------------------

    private static String buildSignature(String ownerFqn, MethodDeclaration m) {        String params = m.getParameters().stream()
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
                log.info("Wiping existing graph...");
                session.run("MATCH (n) DETACH DELETE n");
            }

            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> ingestFile(session, p));
            }
        }
        log.info("Ingestion complete.");
        return 0;
    }

    // ----------------------------------------------------------------
    // Members
    // ----------------------------------------------------------------

    private void ingestFile(Session session, Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            String pkg = cu.getPackageDeclaration()
                .map(pd -> pd.getName().asString())
                .orElse("");

            upsertFile(session, file);
            upsertPackage(session, pkg);

            cu.findAll(ClassOrInterfaceDeclaration.class)
                .forEach(decl -> ingestType(session, file, pkg, decl));

        } catch (Exception e) {
            log.warn("Failed to parse {}: {}", file, e.getMessage());
        }
    }

    private void ingestType(Session session, Path file, String pkg,
        ClassOrInterfaceDeclaration decl) {
        String fqn = pkg.isEmpty() ? decl.getNameAsString() : pkg + "." + decl.getNameAsString();
        String label = decl.isInterface() ? "Interface" : "Class";

        session.run("""
            MERGE (t:%s {fqn: $fqn})
              SET t.name = $name,
                  t.packageName = $pkg,
                  t.isAbstract = $isAbstract
            WITH t
            MATCH (p:Package {name: $pkg})
            MERGE (p)-[:CONTAINS]->(t)
            WITH t
            MATCH (f:File {path: $path})
            MERGE (f)-[:DEFINES]->(t)
            """.formatted(label),
            Map.of("fqn", fqn,
                "name", decl.getNameAsString(),
                "pkg", pkg,
                "path", file.toString(),
                "isAbstract", decl.isAbstract()));

        ingestInheritance(session, fqn, decl);
        decl.getFields().forEach(f -> ingestField(session, fqn, f));
        decl.getMethods().forEach(m -> ingestMethod(session, fqn, m));
    }

    private void ingestInheritance(Session session, String fqn,
        ClassOrInterfaceDeclaration decl) {
        decl.getExtendedTypes().forEach(ext -> tryRun(() -> {
            resolveQualifiedName(ext).ifPresent(parent ->
                session.run("""
                    MERGE (parent:Class {fqn: $parent})
                    WITH parent
                    MATCH (child {fqn: $child})
                    MERGE (child)-[:EXTENDS]->(parent)
                    """, Map.of("child", fqn, "parent", parent)));
        }));

        decl.getImplementedTypes().forEach(impl -> tryRun(() -> {
            resolveQualifiedName(impl).ifPresent(iface ->
                session.run("""
                    MERGE (i:Interface {fqn: $iface})
                    WITH i
                    MATCH (c:Class {fqn: $child})
                    MERGE (c)-[:IMPLEMENTS]->(i)
                    """, Map.of("child", fqn, "iface", iface)));
        }));
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private void ingestField(Session session, String ownerFqn, FieldDeclaration field) {
        field.getVariables().forEach(v -> {
            String fqn = ownerFqn + "#" + v.getNameAsString();
            session.run("""
                MERGE (f:Field {fqn: $fqn})
                  SET f.name = $name,
                      f.type = $type,
                      f.isStatic = $isStatic
                WITH f
                MATCH (owner {fqn: $owner})
                MERGE (owner)-[:DECLARES]->(f)
                """,
                Map.of("fqn", fqn,
                    "name", v.getNameAsString(),
                    "type", v.getTypeAsString(),
                    "isStatic", field.isStatic(),
                    "owner", ownerFqn));
        });
    }

    private void ingestMethod(Session session, String ownerFqn, MethodDeclaration method) {
        String signature = buildSignature(ownerFqn, method);

        session.run("""
            MERGE (m:Method {signature: $sig})
              SET m.name = $name,
                  m.returnType = $ret,
                  m.isStatic = $isStatic,
                  m.startLine = $start,
                  m.endLine = $end
            WITH m
            MATCH (owner {fqn: $owner})
            MERGE (owner)-[:DECLARES]->(m)
            """,
            Map.of("sig", signature,
                "name", method.getNameAsString(),
                "ret", method.getTypeAsString(),
                "isStatic", method.isStatic(),
                "start", method.getBegin().map(p -> p.line).orElse(0),
                "end", method.getEnd().map(p -> p.line).orElse(0),
                "owner", ownerFqn));

        ingestCalls(session, signature, method);
    }

    private void ingestCalls(Session session, String callerSig, MethodDeclaration method) {
        List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);
        for (MethodCallExpr call : calls) {
            tryRun(() -> {
                ResolvedMethodDeclaration resolved = call.resolve();
                String calleeSig = resolved.getQualifiedSignature();
                session.run("""
                    MERGE (callee:Method {signature: $callee})
                    WITH callee
                    MATCH (caller:Method {signature: $caller})
                    MERGE (caller)-[:CALLS]->(callee)
                    """,
                    Map.of("caller", callerSig, "callee", calleeSig));
            });
        }
    }

    private void upsertFile(Session session, Path file) {
        session.run("MERGE (f:File {path: $path})", Map.of("path", file.toString()));
    }

    private void upsertPackage(Session session, String pkg) {
        session.run("MERGE (p:Package {name: $name})", Map.of("name", pkg));
    }
}