package io.github.ousatov.tools.memgraph.exe;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaParser-backed adapter for the existing Java ingestion behavior.
 *
 * @author Oleksii Usatov
 */
public final class JavaLanguageAdapter implements LanguageAdapter {

  private static final Logger log = LoggerFactory.getLogger(JavaLanguageAdapter.class);

  private final ParseService parseService;

  public JavaLanguageAdapter(ParseService parseService) {
    this.parseService = parseService;
  }

  @Override
  public SourceLanguage language() {
    return SourceLanguage.JAVA;
  }

  @Override
  public boolean accepts(Path file) {
    return file.toString().endsWith(".java");
  }

  @Override
  public Optional<SourceFileDefinitions> inspectDefinitions(Path file) {
    var cuOpt = parseService.parse(file);
    if (cuOpt.isEmpty()) {
      return Optional.of(SourceFileDefinitions.empty());
    }
    CompilationUnit cu = cuOpt.get();
    String pkg = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
    return Optional.of(collectDefinitions(pkg, cu));
  }

  @Override
  public boolean ingestFile(GraphWriter writer, Path file) {
    var cuOpt = parseService.parse(file);
    if (cuOpt.isEmpty()) {
      return false;
    }
    CompilationUnit cu = cuOpt.get();
    String pkg = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
    try {
      writer.deleteStaleDefinitionsForFile(file, collectDefinitions(pkg, cu));
      writer.upsertFile(file, language());
      writer.upsertPackage(pkg, language());
      cu.getTypes()
          .forEach(
              typeDecl -> {
                if (typeDecl instanceof ClassOrInterfaceDeclaration ci) {
                  writer.upsertType(file, pkg, ci);
                } else if (typeDecl instanceof EnumDeclaration en) {
                  writer.upsertEnum(file, pkg, en);
                } else if (typeDecl instanceof RecordDeclaration rec) {
                  writer.upsertRecord(file, pkg, rec);
                } else if (typeDecl instanceof AnnotationDeclaration ann) {
                  writer.upsertAnnotation(file, pkg, ann);
                }
              });
      cu.getTypes()
          .forEach(
              typeDecl -> {
                if (typeDecl instanceof ClassOrInterfaceDeclaration ci) {
                  writer.upsertTypeCallEdges(pkg, ci);
                } else if (typeDecl instanceof EnumDeclaration en) {
                  writer.upsertEnumCallEdges(pkg, en);
                } else if (typeDecl instanceof RecordDeclaration rec) {
                  writer.upsertRecordCallEdges(pkg, rec);
                }
              });
      return true;
    } catch (RuntimeException e) {
      if (GraphWriter.isRetryable(e)) {
        throw e;
      }
      log.warn("Failed to ingest {}: {}", file, e.getMessage());
      return false;
    }
  }

  private static SourceFileDefinitions collectDefinitions(String pkg, CompilationUnit cu) {
    Set<String> classFqns = new LinkedHashSet<>();
    Set<String> interfaceFqns = new LinkedHashSet<>();
    Set<String> annotationFqns = new LinkedHashSet<>();
    Set<String> methodSignatures = new LinkedHashSet<>();
    Set<String> fieldFqns = new LinkedHashSet<>();

    cu.getTypes()
        .forEach(
            typeDecl -> {
              if (typeDecl instanceof ClassOrInterfaceDeclaration ci) {
                collectClassOrInterface(
                    pkg, null, ci, classFqns, interfaceFqns, methodSignatures, fieldFqns);
              } else if (typeDecl instanceof EnumDeclaration en) {
                collectEnum(pkg, en, classFqns, interfaceFqns, methodSignatures, fieldFqns);
              } else if (typeDecl instanceof RecordDeclaration rec) {
                collectRecord(pkg, rec, classFqns, interfaceFqns, methodSignatures, fieldFqns);
              } else if (typeDecl instanceof AnnotationDeclaration ann) {
                annotationFqns.add(JavaTypeNames.buildFqn(pkg, ann.getNameAsString()));
              }
            });
    return SourceFileDefinitions.of(
        classFqns, interfaceFqns, annotationFqns, methodSignatures, fieldFqns);
  }

  private static void collectClassOrInterface(
      String pkg,
      String outerFqn,
      ClassOrInterfaceDeclaration decl,
      Set<String> classFqns,
      Set<String> interfaceFqns,
      Set<String> methodSignatures,
      Set<String> fieldFqns) {
    String fqn = typeFqn(pkg, outerFqn, decl.getNameAsString());
    if (decl.isInterface()) {
      interfaceFqns.add(fqn);
    } else {
      classFqns.add(fqn);
    }
    collectFields(fqn, decl.getFields(), fieldFqns);
    collectMethods(fqn, decl.getMethods(), decl.getConstructors(), methodSignatures);
    if (!decl.isInterface() && decl.getConstructors().isEmpty()) {
      methodSignatures.add(fqn + "." + Labels.INIT + "()");
    }
    collectNestedClasses(
        pkg, fqn, decl.getMembers(), classFqns, interfaceFqns, methodSignatures, fieldFqns);
  }

  private static void collectEnum(
      String pkg,
      EnumDeclaration decl,
      Set<String> classFqns,
      Set<String> interfaceFqns,
      Set<String> methodSignatures,
      Set<String> fieldFqns) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    classFqns.add(fqn);
    decl.getEntries().stream()
        .map(EnumConstantDeclaration::getNameAsString)
        .map(name -> fqn + "#" + name)
        .forEach(fieldFqns::add);
    collectFields(fqn, decl.getFields(), fieldFqns);
    collectMethods(fqn, decl.getMethods(), decl.getConstructors(), methodSignatures);
    collectNestedClasses(
        pkg, fqn, decl.getMembers(), classFqns, interfaceFqns, methodSignatures, fieldFqns);
  }

  private static void collectRecord(
      String pkg,
      RecordDeclaration decl,
      Set<String> classFqns,
      Set<String> interfaceFqns,
      Set<String> methodSignatures,
      Set<String> fieldFqns) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    classFqns.add(fqn);
    collectFields(fqn, decl.getFields(), fieldFqns);
    decl.getParameters().stream()
        .map(Parameter::getNameAsString)
        .map(name -> fqn + "#" + name)
        .forEach(fieldFqns::add);
    collectMethods(fqn, decl.getMethods(), decl.getConstructors(), methodSignatures);
    collectRecordCanonicalConstructor(fqn, decl, methodSignatures);
    collectRecordAccessors(fqn, decl, methodSignatures);
    collectNestedClasses(
        pkg, fqn, decl.getMembers(), classFqns, interfaceFqns, methodSignatures, fieldFqns);
  }

  private static void collectFields(
      String ownerFqn, Iterable<FieldDeclaration> fields, Set<String> fieldFqns) {
    fields.forEach(
        field ->
            field.getVariables().stream()
                .map(v -> ownerFqn + "#" + v.getNameAsString())
                .forEach(fieldFqns::add));
  }

  private static void collectMethods(
      String ownerFqn,
      Iterable<MethodDeclaration> methods,
      Iterable<ConstructorDeclaration> constructors,
      Set<String> methodSignatures) {
    methods.forEach(method -> methodSignatures.add(JavaTypeNames.buildSignature(ownerFqn, method)));
    constructors.forEach(
        ctor -> methodSignatures.add(JavaTypeNames.buildConstructorSignature(ownerFqn, ctor)));
  }

  private static void collectRecordCanonicalConstructor(
      String fqn, RecordDeclaration decl, Set<String> methodSignatures) {
    String canonicalParams =
        decl.getParameters().stream()
            .map(JavaTypeNames::resolveParamType)
            .collect(Collectors.joining(", "));
    String canonicalSig = fqn + "." + Labels.INIT + "(" + canonicalParams + ")";
    boolean hasCanonical =
        decl.getConstructors().stream()
            .anyMatch(c -> JavaTypeNames.buildConstructorSignature(fqn, c).equals(canonicalSig));
    if (!hasCanonical) {
      methodSignatures.add(canonicalSig);
    }
  }

  private static void collectRecordAccessors(
      String fqn, RecordDeclaration decl, Set<String> methodSignatures) {
    Set<String> explicitMethods =
        decl.getMethods().stream()
            .filter(m -> m.getParameters().isEmpty())
            .map(MethodDeclaration::getNameAsString)
            .collect(Collectors.toSet());
    decl.getParameters().stream()
        .map(Parameter::getNameAsString)
        .filter(name -> !explicitMethods.contains(name))
        .map(name -> fqn + "." + name + "()")
        .forEach(methodSignatures::add);
  }

  private static void collectNestedClasses(
      String pkg,
      String outerFqn,
      Iterable<BodyDeclaration<?>> members,
      Set<String> classFqns,
      Set<String> interfaceFqns,
      Set<String> methodSignatures,
      Set<String> fieldFqns) {
    members.forEach(
        member -> {
          if (member instanceof ClassOrInterfaceDeclaration nested) {
            collectClassOrInterface(
                pkg, outerFqn, nested, classFqns, interfaceFqns, methodSignatures, fieldFqns);
          }
        });
  }

  private static String typeFqn(String pkg, String outerFqn, String simpleName) {
    return outerFqn != null ? outerFqn + "$" + simpleName : JavaTypeNames.buildFqn(pkg, simpleName);
  }
}
