package io.github.ousatov.tools.memgraph.exe.writer.java;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exe.analyze.JavaTypeNames;
import io.github.ousatov.tools.memgraph.exe.writer.CommonGraphWriter;
import io.github.ousatov.tools.memgraph.vo.Method;
import io.github.ousatov.tools.memgraph.vo.writer.AnnotationNodeWrite;
import io.github.ousatov.tools.memgraph.vo.writer.AnnotationWrite;
import io.github.ousatov.tools.memgraph.vo.writer.ClassWrite;
import io.github.ousatov.tools.memgraph.vo.writer.FieldWrite;
import io.github.ousatov.tools.memgraph.vo.writer.InterfaceWrite;
import io.github.ousatov.tools.memgraph.vo.writer.TypeRelationWrite;
import io.github.ousatov.tools.memgraph.vo.writer.TypeStructureWrites;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Writes Java-specific graph structures discovered from JavaParser declarations.
 *
 * @author Oleksii Usatov
 */
public final class JavaGraphWriter extends CommonGraphWriter {

  public JavaGraphWriter(Dependencies dependencies) {
    super(dependencies);
  }

  /**
   * Upserts a class or interface declaration and all of its members, including directly nested
   * types with their correct {@code $}-separated FQN.
   */
  public void upsertType(Path file, String pkg, ClassOrInterfaceDeclaration decl) {
    TypeStructureWrites writes = new TypeStructureWrites();
    collectTypeStructure(file, pkg, null, decl, writes);
    upsertTypeStructure(writes);
    upsertTypeMembers(file, pkg, null, decl);
  }

  /**
   * Upserts an enum declaration as a {@code :Class} with {@code isEnum = true}, including its
   * constants, fields, methods, constructors, implemented interfaces, and nested types.
   */
  public void upsertEnum(Path file, String pkg, EnumDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    TypeStructureWrites writes = new TypeStructureWrites();
    writes
        .classes()
        .add(
            new ClassWrite(
                file,
                pkg,
                fqn,
                decl.getNameAsString(),
                false,
                decl.getAccessSpecifier().asString(),
                true,
                false,
                true,
                JAVA_LANGUAGE,
                Params.ENUM,
                Const.Symbols.EMPTY,
                Const.Symbols.EMPTY));
    collectImplementedTypes(fqn, decl, writes);
    collectNestedTypeStructure(file, pkg, fqn, decl.getMembers(), writes);
    upsertTypeStructure(writes);
    upsertAnnotationsByFqn(fqn, decl);
    decl.getEntries().forEach(entry -> upsertEnumConstant(file, fqn, entry));
    decl.getFields().forEach(f -> upsertField(file, fqn, f));
    upsertDeclaredMethods(file, fqn, decl.getMethods(), decl.getConstructors());
    upsertNestedTypeMembers(file, pkg, fqn, decl.getMembers());
  }

  /**
   * Upserts a record declaration as a {@code :Class} with {@code isRecord = true}, including its
   * fields, methods, constructors, implemented interfaces, and nested types.
   */
  public void upsertRecord(Path file, String pkg, RecordDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    TypeStructureWrites writes = new TypeStructureWrites();
    writes
        .classes()
        .add(
            new ClassWrite(
                file,
                pkg,
                fqn,
                decl.getNameAsString(),
                false,
                decl.getAccessSpecifier().asString(),
                false,
                true,
                true,
                JAVA_LANGUAGE,
                Params.RECORD,
                Const.Symbols.EMPTY,
                Const.Symbols.EMPTY));
    collectImplementedTypes(fqn, decl, writes);
    collectNestedTypeStructure(file, pkg, fqn, decl.getMembers(), writes);
    upsertTypeStructure(writes);
    upsertAnnotationsByFqn(fqn, decl);
    decl.getFields().forEach(f -> upsertField(file, fqn, f));
    upsertRecordComponents(file, fqn, decl);
    upsertDeclaredMethods(file, fqn, decl.getMethods(), decl.getConstructors());
    upsertRecordCanonicalConstructor(file, fqn, decl);
    upsertRecordAccessors(file, fqn, decl);
    upsertNestedTypeMembers(file, pkg, fqn, decl.getMembers());
  }

  /**
   * Upserts an {@code @interface} declaration as an {@code :Annotation} node, including {@code
   * ANNOTATED_WITH} edges for any meta-annotations applied to it.
   */
  public void upsertAnnotation(Path file, String pkg, AnnotationDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    upsertAnnotationNodes(
        List.of(
            new AnnotationNodeWrite(
                file,
                pkg,
                fqn,
                decl.getNameAsString(),
                decl.getAccessSpecifier().asString(),
                JAVA_LANGUAGE,
                Params.ANNOTATION,
                Const.Symbols.EMPTY,
                Const.Symbols.EMPTY)));
    upsertAnnotationsByFqn(fqn, decl);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including
   * directly nested types. Call this after all structural upserts for the file are complete, so
   * every callee node already exists.
   */
  public void upsertTypeCallEdges(String pkg, ClassOrInterfaceDeclaration decl) {
    upsertTypeCallEdgesInternal(pkg, null, decl);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertEnumCallEdges(String pkg, EnumDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    upsertCallEdgesForDecl(pkg, fqn, decl.getMethods(), decl.getConstructors(), decl.getMembers());
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertRecordCallEdges(String pkg, RecordDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    upsertCallEdgesForDecl(pkg, fqn, decl.getMethods(), decl.getConstructors(), decl.getMembers());
  }

  private static String typeFqn(String pkg, String outerFqn, String simpleName) {
    return outerFqn != null
        ? outerFqn + Const.Symbols.DOLLAR + simpleName
        : JavaTypeNames.buildFqn(pkg, simpleName);
  }

  private void upsertTypeCallEdgesInternal(
      String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn = typeFqn(pkg, outerFqn, decl.getNameAsString());
    decl.getMethods().forEach(m -> upsertCallEdge(JavaTypeNames.buildSignature(fqn, m), fqn, m));
    decl.getConstructors()
        .forEach(c -> upsertCallEdge(JavaTypeNames.buildConstructorSignature(fqn, c), fqn, c));
    nestedClassDeclarationsOf(decl.getMembers())
        .forEach(nested -> upsertTypeCallEdgesInternal(pkg, fqn, nested));
  }

  private void collectTypeStructure(
      Path file,
      String pkg,
      String outerFqn,
      ClassOrInterfaceDeclaration decl,
      TypeStructureWrites writes) {
    String fqn = typeFqn(pkg, outerFqn, decl.getNameAsString());
    if (decl.isInterface()) {
      writes
          .interfaces()
          .add(
              new InterfaceWrite(
                  file,
                  pkg,
                  fqn,
                  decl.getNameAsString(),
                  decl.isAbstract(),
                  decl.getAccessSpecifier().asString(),
                  false,
                  JAVA_LANGUAGE,
                  Params.INTERFACE,
                  Const.Symbols.EMPTY,
                  Const.Symbols.EMPTY));
    } else {
      writes
          .classes()
          .add(
              new ClassWrite(
                  file,
                  pkg,
                  fqn,
                  decl.getNameAsString(),
                  decl.isAbstract(),
                  decl.getAccessSpecifier().asString(),
                  false,
                  false,
                  decl.isFinal(),
                  JAVA_LANGUAGE,
                  Params.CLASS,
                  Const.Symbols.EMPTY,
                  Const.Symbols.EMPTY));
    }
    collectInheritance(fqn, decl, writes);
    collectNestedTypeStructure(file, pkg, fqn, decl.getMembers(), writes);
  }

  private void upsertTypeMembers(
      Path file, String pkg, String outerFqn, ClassOrInterfaceDeclaration decl) {
    String fqn = typeFqn(pkg, outerFqn, decl.getNameAsString());
    upsertAnnotationsByFqn(fqn, decl);
    decl.getFields().forEach(f -> upsertField(file, fqn, f));
    upsertDeclaredMethods(file, fqn, decl.getMethods(), decl.getConstructors());
    if (!decl.isInterface() && decl.getConstructors().isEmpty()) {
      upsertImplicitDefaultConstructor(file, fqn, decl);
    }
    upsertNestedTypeMembers(file, pkg, fqn, decl.getMembers());
  }

  private void collectNestedTypeStructure(
      Path file, String pkg, String outerFqn, List<?> members, TypeStructureWrites writes) {
    nestedClassDeclarationsOf(members)
        .forEach(nested -> collectTypeStructure(file, pkg, outerFqn, nested, writes));
  }

  private void upsertNestedTypeMembers(Path file, String pkg, String outerFqn, List<?> members) {
    nestedClassDeclarationsOf(members)
        .forEach(nested -> upsertTypeMembers(file, pkg, outerFqn, nested));
  }

  private void upsertTypeStructure(TypeStructureWrites writes) {
    upsertClassNodes(writes.classes());
    upsertInterfaceNodes(writes.interfaces());
    upsertClassExtends(writes.classExtends());
    upsertInterfaceExtends(writes.interfaceExtends());
    upsertImplements(writes.implementsRelations());
  }

  private static Stream<ClassOrInterfaceDeclaration> nestedClassDeclarationsOf(List<?> members) {
    return members.stream()
        .filter(ClassOrInterfaceDeclaration.class::isInstance)
        .map(ClassOrInterfaceDeclaration.class::cast);
  }

  private void upsertCallEdgesForDecl(
      String pkg,
      String fqn,
      List<MethodDeclaration> methods,
      List<ConstructorDeclaration> constructors,
      List<?> members) {
    methods.forEach(m -> upsertCallEdge(JavaTypeNames.buildSignature(fqn, m), fqn, m));
    constructors.forEach(
        c -> upsertCallEdge(JavaTypeNames.buildConstructorSignature(fqn, c), fqn, c));
    nestedClassDeclarationsOf(members)
        .forEach(nested -> upsertTypeCallEdgesInternal(pkg, fqn, nested));
  }

  private void collectInheritance(
      String fqn, ClassOrInterfaceDeclaration decl, TypeStructureWrites writes) {
    Consumer<TypeRelationWrite> extendsSink =
        decl.isInterface() ? writes.interfaceExtends()::add : writes.classExtends()::add;
    decl.getExtendedTypes().forEach(ext -> collectTypeRelation(fqn, ext, extendsSink));
    collectImplementedTypes(fqn, decl, writes);
  }

  private void collectImplementedTypes(
      String fqn, NodeWithImplements<?> decl, TypeStructureWrites writes) {
    decl.getImplementedTypes()
        .forEach(impl -> collectTypeRelation(fqn, impl, writes.implementsRelations()::add));
  }

  private static void collectTypeRelation(
      String childFqn, ClassOrInterfaceType target, Consumer<TypeRelationWrite> sink) {
    JavaTypeNames.withResolvedType(
        target,
        targetFqn -> sink.accept(new TypeRelationWrite(childFqn, targetFqn, JAVA_LANGUAGE)));
  }

  private void upsertRecordComponents(Path file, String ownerFqn, RecordDeclaration decl) {
    List<FieldWrite> fields = new ArrayList<>();
    for (Parameter param : decl.getParameters()) {
      fields.add(
          new FieldWrite(
              ownerFqn,
              ownerFqn + Const.Symbols.HASH + param.getNameAsString(),
              param.getNameAsString(),
              JavaTypeNames.resolveType(param.getType()),
              false,
              "private",
              JAVA_LANGUAGE,
              Const.Params.RECORD_COMPONENT));
    }
    upsertFieldNodes(file, fields);
    for (Parameter param : decl.getParameters()) {
      String fqn = ownerFqn + Const.Symbols.HASH + param.getNameAsString();
      upsertAnnotationsByFqn(fqn, param);
    }
  }

  private void upsertField(Path file, String ownerFqn, FieldDeclaration field) {
    List<FieldWrite> fields =
        field.getVariables().stream()
            .map(
                v ->
                    new FieldWrite(
                        ownerFqn,
                        ownerFqn + Const.Symbols.HASH + v.getNameAsString(),
                        v.getNameAsString(),
                        JavaTypeNames.resolveType(v.getType()),
                        field.isStatic(),
                        field.getAccessSpecifier().asString(),
                        JAVA_LANGUAGE,
                        Params.FIELD))
            .toList();
    upsertFieldNodes(file, fields);
    fields.forEach(f -> upsertAnnotationsByFqn(f.fqn(), field));
  }

  private void upsertEnumConstant(Path file, String ownerFqn, EnumConstantDeclaration entry) {
    upsertFieldNodes(
        file,
        List.of(
            new FieldWrite(
                ownerFqn,
                ownerFqn + Const.Symbols.HASH + entry.getNameAsString(),
                entry.getNameAsString(),
                ownerFqn,
                true,
                Params.PUBLIC,
                JAVA_LANGUAGE,
                Params.ENUM_MEMBER)));
  }

  private void upsertDeclaredMethods(
      Path file,
      String ownerFqn,
      List<MethodDeclaration> methods,
      List<ConstructorDeclaration> constructors) {
    List<Method> writes = new ArrayList<>(methods.size() + constructors.size());
    methods.forEach(method -> writes.add(methodWrite(ownerFqn, method)));
    constructors.forEach(ctor -> writes.add(constructorWrite(ownerFqn, ctor)));
    upsertMethodNodes(file, writes);
    methods.forEach(
        method -> upsertAnnotationsBySig(JavaTypeNames.buildSignature(ownerFqn, method), method));
    constructors.forEach(
        ctor ->
            upsertAnnotationsBySig(JavaTypeNames.buildConstructorSignature(ownerFqn, ctor), ctor));
  }

  private static Method methodWrite(String ownerFqn, MethodDeclaration method) {
    return new Method(
        ownerFqn,
        JavaTypeNames.buildSignature(ownerFqn, method),
        method.getNameAsString(),
        JavaTypeNames.resolveType(method.getType()),
        method.isStatic(),
        method.getAccessSpecifier().asString(),
        method.getBegin().map(p -> p.line).orElse(0),
        method.getEnd().map(p -> p.line).orElse(0),
        false);
  }

  private static Method constructorWrite(String ownerFqn, ConstructorDeclaration ctor) {
    return new Method(
        ownerFqn,
        JavaTypeNames.buildConstructorSignature(ownerFqn, ctor),
        Labels.INIT,
        Labels.VOID,
        false,
        ctor.getAccessSpecifier().asString(),
        ctor.getBegin().map(p -> p.line).orElse(0),
        ctor.getEnd().map(p -> p.line).orElse(0),
        false);
  }

  private void upsertRecordCanonicalConstructor(Path file, String fqn, RecordDeclaration decl) {
    if (!JavaTypeNames.hasExplicitCanonicalConstructor(fqn, decl)) {
      upsertMethodNode(
          file,
          new Method(
              fqn,
              JavaTypeNames.buildRecordCanonicalConstructorSignature(fqn, decl),
              Labels.INIT,
              Labels.VOID,
              false,
              decl.getAccessSpecifier().asString(),
              0,
              0,
              true));
    }
  }

  private void upsertImplicitDefaultConstructor(
      Path file, String fqn, ClassOrInterfaceDeclaration decl) {
    upsertMethodNode(
        file,
        new Method(
            fqn,
            fqn + Const.Symbols.DOT + Labels.INIT + Const.Symbols.PARENS,
            Labels.INIT,
            Labels.VOID,
            false,
            decl.getAccessSpecifier().asString(),
            0,
            0,
            true));
  }

  private void upsertRecordAccessors(Path file, String fqn, RecordDeclaration decl) {
    var explicitMethods =
        decl.getMethods().stream()
            .filter(m -> m.getParameters().isEmpty())
            .map(MethodDeclaration::getNameAsString)
            .collect(Collectors.toSet());

    for (Parameter param : decl.getParameters()) {
      String accessorName = param.getNameAsString();
      if (!explicitMethods.contains(accessorName)) {
        String sig = fqn + Const.Symbols.DOT + accessorName + Const.Symbols.PARENS;
        upsertMethodNode(
            file,
            new Method(
                fqn,
                sig,
                accessorName,
                JavaTypeNames.resolveType(param.getType()),
                false,
                Params.PUBLIC,
                0,
                0,
                true));
      }
    }
  }

  private void upsertAnnotationsByFqn(String ownerFqn, NodeWithAnnotations<?> node) {
    upsertAnnotations(Params.OWNER, ownerFqn, node);
  }

  private void upsertAnnotationsBySig(String sig, NodeWithAnnotations<?> node) {
    upsertAnnotations(Params.SIG, sig, node);
  }

  private void upsertAnnotations(String paramKey, String paramValue, NodeWithAnnotations<?> node) {
    List<AnnotationWrite> annotations =
        node.getAnnotations().stream()
            .map(
                ann -> {
                  String annotFqn;
                  try {
                    annotFqn = JavaTypeNames.normalizeNestedFqn(ann.resolve().getQualifiedName());
                  } catch (Exception _) {
                    annotFqn = ann.getNameAsString();
                  }
                  return new AnnotationWrite(
                      paramValue,
                      annotFqn,
                      JavaTypeNames.nameFromFqn(annotFqn),
                      JAVA_LANGUAGE,
                      Params.ANNOTATION);
                })
            .toList();
    if (Params.OWNER.equals(paramKey)) {
      upsertAnnotationReferencesByFqn(annotations);
    } else {
      upsertAnnotationReferencesBySig(annotations);
    }
  }
}
