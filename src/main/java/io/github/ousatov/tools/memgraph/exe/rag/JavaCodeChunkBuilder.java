package io.github.ousatov.tools.memgraph.exe.rag;

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
import com.github.javaparser.ast.body.TypeDeclaration;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.exe.analyze.JavaTypeNames;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.MemberChunk;
import io.github.ousatov.tools.memgraph.exe.rag.CodeChunkAnalysis.TypeChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds derived {@code :CodeChunk} rows from JavaParser compilation units.
 *
 * @author Oleksii Usatov
 */
public final class JavaCodeChunkBuilder extends CommonCodeChunkBuilder<CompilationUnit> {

  private static final String LANGUAGE = SourceLanguage.JAVA.graphName();

  public JavaCodeChunkBuilder() {
    super(new JavaChunkAnalyzer());
  }

  private static final class JavaChunkAnalyzer implements CodeChunkAnalyzer<CompilationUnit> {

    private static void addTopLevelType(
        List<TypeChunk> types, List<MemberChunk> members, String pkg, TypeDeclaration<?> typeDecl) {
      if (typeDecl instanceof ClassOrInterfaceDeclaration ci) {
        addClassOrInterface(types, members, pkg, null, ci);
      } else if (typeDecl instanceof EnumDeclaration en) {
        addEnum(types, members, pkg, en);
      } else if (typeDecl instanceof RecordDeclaration rec) {
        addRecord(types, members, pkg, rec);
      } else if (typeDecl instanceof AnnotationDeclaration ann) {
        String fqn = JavaTypeNames.buildFqn(pkg, ann.getNameAsString());
        types.add(typeChunk("Annotation", fqn, ann, ann.getNameAsString()));
      }
    }

    @Override
    public CodeChunkAnalysis analyze(CompilationUnit cu) {
      String pkg = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
      List<TypeChunk> types = new ArrayList<>();
      List<MemberChunk> members = new ArrayList<>();
      cu.getTypes().forEach(typeDecl -> addTopLevelType(types, members, pkg, typeDecl));
      return new CodeChunkAnalysis(
          LANGUAGE, "", "", 0, 0, List.copyOf(types), List.copyOf(members));
    }
  }

  private static void addClassOrInterface(
      List<TypeChunk> types,
      List<MemberChunk> members,
      String pkg,
      String outerFqn,
      ClassOrInterfaceDeclaration decl) {
    String fqn = typeFqn(pkg, outerFqn, decl.getNameAsString());
    String label = decl.isInterface() ? "Interface" : "Class";
    types.add(typeChunk(label, fqn, decl, decl.getNameAsString()));
    decl.getFields().forEach(field -> addField(members, fqn, field));
    decl.getMethods().forEach(method -> addMethod(members, fqn, method));
    decl.getConstructors().forEach(ctor -> addConstructor(members, fqn, ctor));
    nestedClassDeclarationsOf(decl.getMembers())
        .forEach(nested -> addClassOrInterface(types, members, pkg, fqn, nested));
  }

  private static void addEnum(
      List<TypeChunk> types, List<MemberChunk> members, String pkg, EnumDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    types.add(typeChunk("Class", fqn, decl, decl.getNameAsString()));
    decl.getEntries().forEach(entry -> addEnumConstant(members, fqn, entry));
    decl.getFields().forEach(field -> addField(members, fqn, field));
    decl.getMethods().forEach(method -> addMethod(members, fqn, method));
    decl.getConstructors().forEach(ctor -> addConstructor(members, fqn, ctor));
    nestedClassDeclarationsOf(decl.getMembers())
        .forEach(nested -> addClassOrInterface(types, members, pkg, fqn, nested));
  }

  private static void addRecord(
      List<TypeChunk> types, List<MemberChunk> members, String pkg, RecordDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    types.add(typeChunk("Class", fqn, decl, decl.getNameAsString()));
    decl.getParameters().forEach(param -> addRecordComponent(members, fqn, param));
    decl.getFields().forEach(field -> addField(members, fqn, field));
    decl.getMethods().forEach(method -> addMethod(members, fqn, method));
    decl.getConstructors().forEach(ctor -> addConstructor(members, fqn, ctor));
    addRecordCanonicalConstructor(members, fqn, decl);
    addRecordAccessors(members, fqn, decl);
    nestedClassDeclarationsOf(decl.getMembers())
        .forEach(nested -> addClassOrInterface(types, members, pkg, fqn, nested));
  }

  private static TypeChunk typeChunk(
      String sourceLabel, String fqn, BodyDeclaration<?> decl, String name) {
    return new TypeChunk(
        sourceLabel,
        fqn,
        fqn,
        name,
        sourceLabel.toLowerCase(Locale.ROOT),
        beginLineOf(decl),
        endLineOf(decl));
  }

  private static void addField(List<MemberChunk> members, String ownerFqn, FieldDeclaration field) {
    field
        .getVariables()
        .forEach(
            variable ->
                members.add(
                    new MemberChunk(
                        ownerFqn,
                        Params.FIELD,
                        Params.FIELD,
                        ownerFqn + "#" + variable.getNameAsString(),
                        variable.getNameAsString(),
                        beginLineOf(field),
                        endLineOf(field))));
  }

  private static void addRecordComponent(
      List<MemberChunk> members, String ownerFqn, Parameter param) {
    members.add(
        new MemberChunk(
            ownerFqn,
            Params.FIELD,
            "record-component",
            ownerFqn + "#" + param.getNameAsString(),
            param.getNameAsString(),
            beginLineOf(param),
            endLineOf(param)));
  }

  private static void addEnumConstant(
      List<MemberChunk> members, String ownerFqn, EnumConstantDeclaration entry) {
    members.add(
        new MemberChunk(
            ownerFqn,
            Params.FIELD,
            Params.ENUM_MEMBER,
            ownerFqn + "#" + entry.getNameAsString(),
            entry.getNameAsString(),
            beginLineOf(entry),
            endLineOf(entry)));
  }

  private static void addMethod(
      List<MemberChunk> members, String ownerFqn, MethodDeclaration method) {
    String signature = JavaTypeNames.buildSignature(ownerFqn, method);
    members.add(
        new MemberChunk(
            ownerFqn,
            Params.METHOD,
            Params.METHOD,
            signature,
            method.getNameAsString(),
            beginLineOf(method),
            endLineOf(method)));
  }

  private static void addConstructor(
      List<MemberChunk> members, String ownerFqn, ConstructorDeclaration ctor) {
    String signature = JavaTypeNames.buildConstructorSignature(ownerFqn, ctor);
    members.add(
        new MemberChunk(
            ownerFqn,
            Params.CONSTRUCTOR,
            Params.CONSTRUCTOR,
            signature,
            Labels.INIT,
            beginLineOf(ctor),
            endLineOf(ctor)));
  }

  private static void addRecordCanonicalConstructor(
      List<MemberChunk> members, String ownerFqn, RecordDeclaration decl) {
    String canonicalParams =
        decl.getParameters().stream()
            .map(JavaTypeNames::resolveParamType)
            .collect(Collectors.joining(", "));
    String canonicalSig = ownerFqn + "." + Labels.INIT + "(" + canonicalParams + ")";
    boolean hasCanonical =
        decl.getConstructors().stream()
            .anyMatch(
                c -> JavaTypeNames.buildConstructorSignature(ownerFqn, c).equals(canonicalSig));
    if (!hasCanonical) {
      members.add(
          new MemberChunk(
              ownerFqn,
              Params.CONSTRUCTOR,
              Params.CONSTRUCTOR,
              canonicalSig,
              Labels.INIT,
              beginLineOf(decl),
              endLineOf(decl)));
    }
  }

  private static void addRecordAccessors(
      List<MemberChunk> members, String ownerFqn, RecordDeclaration decl) {
    Set<String> explicitMethods =
        decl.getMethods().stream()
            .filter(method -> method.getParameters().isEmpty())
            .map(MethodDeclaration::getNameAsString)
            .collect(Collectors.toSet());
    decl.getParameters().stream()
        .filter(param -> !explicitMethods.contains(param.getNameAsString()))
        .forEach(param -> addRecordAccessor(members, ownerFqn, param));
  }

  private static void addRecordAccessor(
      List<MemberChunk> members, String ownerFqn, Parameter param) {
    String accessorName = param.getNameAsString();
    members.add(
        new MemberChunk(
            ownerFqn,
            Params.METHOD,
            Params.METHOD,
            ownerFqn + "." + accessorName + "()",
            accessorName,
            beginLineOf(param),
            endLineOf(param)));
  }

  private static Stream<ClassOrInterfaceDeclaration> nestedClassDeclarationsOf(
      List<BodyDeclaration<?>> members) {
    return members.stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(ClassOrInterfaceDeclaration.class::cast);
  }

  private static String typeFqn(String pkg, String outerFqn, String simpleName) {
    return outerFqn == null ? JavaTypeNames.buildFqn(pkg, simpleName) : outerFqn + "$" + simpleName;
  }

  private static int beginLineOf(com.github.javaparser.ast.Node node) {
    return node.getBegin().map(position -> position.line).orElse(0);
  }

  private static int endLineOf(com.github.javaparser.ast.Node node) {
    return node.getEnd().map(position -> position.line).orElse(0);
  }
}
