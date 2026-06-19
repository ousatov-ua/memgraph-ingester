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
import io.github.ousatov.tools.memgraph.vo.writer.CallWrite;
import io.github.ousatov.tools.memgraph.vo.writer.ClassWrite;
import io.github.ousatov.tools.memgraph.vo.writer.FieldWrite;
import io.github.ousatov.tools.memgraph.vo.writer.InterfaceWrite;
import io.github.ousatov.tools.memgraph.vo.writer.PendingCallWrite;
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
    JavaMemberWrites memberWrites = new JavaMemberWrites();
    collectTypeMembers(pkg, null, decl, memberWrites);
    upsertJavaMembers(file, memberWrites);
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
    JavaMemberWrites memberWrites = new JavaMemberWrites();
    collectEnumMembers(pkg, fqn, decl, memberWrites);
    upsertJavaMembers(file, memberWrites);
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
    JavaMemberWrites memberWrites = new JavaMemberWrites();
    collectRecordMembers(pkg, fqn, decl, memberWrites);
    upsertJavaMembers(file, memberWrites);
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
    upsertAnnotationReferencesByFqn(annotationWrites(fqn, decl, Labels.ANNOTATION));
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including
   * directly nested types. Call this after all structural upserts for the file are complete, so
   * every callee node already exists.
   */
  public void upsertTypeCallEdges(String pkg, ClassOrInterfaceDeclaration decl) {
    List<CallWrite> resolvedCalls = new ArrayList<>();
    List<PendingCallWrite> nameCalls = new ArrayList<>();
    collectTypeCallEdgesInternal(pkg, null, decl, resolvedCalls, nameCalls);
    upsertCalls(resolvedCalls);
    upsertCallsByName(nameCalls);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertEnumCallEdges(String pkg, EnumDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    List<CallWrite> resolvedCalls = new ArrayList<>();
    List<PendingCallWrite> nameCalls = new ArrayList<>();
    collectCallEdgesForDecl(
        pkg,
        fqn,
        decl.getMethods(),
        decl.getConstructors(),
        decl.getMembers(),
        resolvedCalls,
        nameCalls);
    upsertCalls(resolvedCalls);
    upsertCallsByName(nameCalls);
  }

  /**
   * Upserts {@code CALLS} edges for all methods and constructors in {@code decl}, including nested
   * types. Call this after all structural upserts for the file are complete.
   */
  public void upsertRecordCallEdges(String pkg, RecordDeclaration decl) {
    String fqn = JavaTypeNames.buildFqn(pkg, decl.getNameAsString());
    List<CallWrite> resolvedCalls = new ArrayList<>();
    List<PendingCallWrite> nameCalls = new ArrayList<>();
    collectCallEdgesForDecl(
        pkg,
        fqn,
        decl.getMethods(),
        decl.getConstructors(),
        decl.getMembers(),
        resolvedCalls,
        nameCalls);
    upsertCalls(resolvedCalls);
    upsertCallsByName(nameCalls);
  }

  private static String typeFqn(String pkg, String outerFqn, String simpleName) {
    return outerFqn != null
        ? outerFqn + Const.Symbols.DOLLAR + simpleName
        : JavaTypeNames.buildFqn(pkg, simpleName);
  }

  private void collectTypeCallEdgesInternal(
      String pkg,
      String outerFqn,
      ClassOrInterfaceDeclaration decl,
      List<CallWrite> resolvedCalls,
      List<PendingCallWrite> nameCalls) {
    String fqn = typeFqn(pkg, outerFqn, decl.getNameAsString());
    decl.getMethods()
        .forEach(
            m ->
                collectCallEdges(
                    resolvedCalls, nameCalls, JavaTypeNames.buildSignature(fqn, m), fqn, m));
    decl.getConstructors()
        .forEach(
            c ->
                collectCallEdges(
                    resolvedCalls,
                    nameCalls,
                    JavaTypeNames.buildConstructorSignature(fqn, c),
                    fqn,
                    c));
    nestedClassDeclarationsOf(decl.getMembers())
        .forEach(
            nested -> collectTypeCallEdgesInternal(pkg, fqn, nested, resolvedCalls, nameCalls));
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

  private void collectTypeMembers(
      String pkg, String outerFqn, ClassOrInterfaceDeclaration decl, JavaMemberWrites writes) {
    String fqn = typeFqn(pkg, outerFqn, decl.getNameAsString());
    String ownerKind = decl.isInterface() ? Labels.INTERFACE : Labels.CLASS;
    writes.fqnAnnotations().addAll(annotationWrites(fqn, decl, ownerKind));
    decl.getFields().forEach(f -> collectField(fqn, ownerKind, f, writes));
    collectDeclaredMethods(fqn, ownerKind, decl.getMethods(), decl.getConstructors(), writes);
    if (!decl.isInterface() && decl.getConstructors().isEmpty()) {
      collectImplicitDefaultConstructor(fqn, decl, writes);
    }
    collectNestedTypeMembers(pkg, fqn, decl.getMembers(), writes);
  }

  private void collectNestedTypeStructure(
      Path file, String pkg, String outerFqn, List<?> members, TypeStructureWrites writes) {
    nestedClassDeclarationsOf(members)
        .forEach(nested -> collectTypeStructure(file, pkg, outerFqn, nested, writes));
  }

  private void collectNestedTypeMembers(
      String pkg, String outerFqn, List<?> members, JavaMemberWrites writes) {
    nestedClassDeclarationsOf(members)
        .forEach(nested -> collectTypeMembers(pkg, outerFqn, nested, writes));
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

  private void collectCallEdgesForDecl(
      String pkg,
      String fqn,
      List<MethodDeclaration> methods,
      List<ConstructorDeclaration> constructors,
      List<?> members,
      List<CallWrite> resolvedCalls,
      List<PendingCallWrite> nameCalls) {
    methods.forEach(
        m ->
            collectCallEdges(
                resolvedCalls, nameCalls, JavaTypeNames.buildSignature(fqn, m), fqn, m));
    constructors.forEach(
        c ->
            collectCallEdges(
                resolvedCalls, nameCalls, JavaTypeNames.buildConstructorSignature(fqn, c), fqn, c));
    nestedClassDeclarationsOf(members)
        .forEach(
            nested -> collectTypeCallEdgesInternal(pkg, fqn, nested, resolvedCalls, nameCalls));
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

  private void collectRecordMembers(
      String pkg, String fqn, RecordDeclaration decl, JavaMemberWrites writes) {
    writes.fqnAnnotations().addAll(annotationWrites(fqn, decl, Labels.CLASS));
    decl.getFields().forEach(f -> collectField(fqn, Labels.CLASS, f, writes));
    collectRecordComponents(fqn, decl, writes);
    collectDeclaredMethods(fqn, Labels.CLASS, decl.getMethods(), decl.getConstructors(), writes);
    collectRecordCanonicalConstructor(fqn, decl, writes);
    collectRecordAccessors(fqn, decl, writes);
    collectNestedTypeMembers(pkg, fqn, decl.getMembers(), writes);
  }

  private void collectEnumMembers(
      String pkg, String fqn, EnumDeclaration decl, JavaMemberWrites writes) {
    writes.fqnAnnotations().addAll(annotationWrites(fqn, decl, Labels.CLASS));
    decl.getEntries().forEach(entry -> collectEnumConstant(fqn, entry, writes));
    decl.getFields().forEach(f -> collectField(fqn, Labels.CLASS, f, writes));
    collectDeclaredMethods(fqn, Labels.CLASS, decl.getMethods(), decl.getConstructors(), writes);
    collectNestedTypeMembers(pkg, fqn, decl.getMembers(), writes);
  }

  private void collectRecordComponents(
      String ownerFqn, RecordDeclaration decl, JavaMemberWrites writes) {
    for (Parameter param : decl.getParameters()) {
      writes
          .fields()
          .add(
              new FieldWrite(
                  ownerFqn,
                  ownerFqn + Const.Symbols.HASH + param.getNameAsString(),
                  param.getNameAsString(),
                  JavaTypeNames.resolveType(param.getType()),
                  false,
                  "private",
                  JAVA_LANGUAGE,
                  Const.Params.RECORD_COMPONENT,
                  Labels.CLASS));
      String fqn = ownerFqn + Const.Symbols.HASH + param.getNameAsString();
      writes.fqnAnnotations().addAll(annotationWrites(fqn, param, Labels.FIELD));
    }
  }

  private void collectField(
      String ownerFqn, String ownerKind, FieldDeclaration field, JavaMemberWrites writes) {
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
                        Params.FIELD,
                        ownerKind))
            .toList();
    writes.fields().addAll(fields);
    fields.forEach(
        f -> writes.fqnAnnotations().addAll(annotationWrites(f.fqn(), field, Labels.FIELD)));
  }

  private void collectEnumConstant(
      String ownerFqn, EnumConstantDeclaration entry, JavaMemberWrites writes) {
    writes
        .fields()
        .add(
            new FieldWrite(
                ownerFqn,
                ownerFqn + Const.Symbols.HASH + entry.getNameAsString(),
                entry.getNameAsString(),
                ownerFqn,
                true,
                Params.PUBLIC,
                JAVA_LANGUAGE,
                Params.ENUM_MEMBER,
                Labels.CLASS));
  }

  private void collectDeclaredMethods(
      String ownerFqn,
      String ownerKind,
      List<MethodDeclaration> methods,
      List<ConstructorDeclaration> constructors,
      JavaMemberWrites writes) {
    methods.forEach(method -> writes.methods().add(methodWrite(ownerFqn, ownerKind, method)));
    constructors.forEach(ctor -> writes.methods().add(constructorWrite(ownerFqn, ownerKind, ctor)));
    methods.forEach(
        method ->
            writes
                .sigAnnotations()
                .addAll(
                    annotationWrites(
                        JavaTypeNames.buildSignature(ownerFqn, method), method, Labels.METHOD)));
    constructors.forEach(
        ctor ->
            writes
                .sigAnnotations()
                .addAll(
                    annotationWrites(
                        JavaTypeNames.buildConstructorSignature(ownerFqn, ctor),
                        ctor,
                        Labels.METHOD)));
  }

  private static Method methodWrite(String ownerFqn, String ownerKind, MethodDeclaration method) {
    return new Method(
        ownerFqn,
        JavaTypeNames.buildSignature(ownerFqn, method),
        method.getNameAsString(),
        JavaTypeNames.resolveType(method.getType()),
        method.isStatic(),
        method.getAccessSpecifier().asString(),
        method.getBegin().map(p -> p.line).orElse(0),
        method.getEnd().map(p -> p.line).orElse(0),
        false,
        JAVA_LANGUAGE,
        Params.METHOD,
        ownerKind);
  }

  private static Method constructorWrite(
      String ownerFqn, String ownerKind, ConstructorDeclaration ctor) {
    return new Method(
        ownerFqn,
        JavaTypeNames.buildConstructorSignature(ownerFqn, ctor),
        Labels.INIT,
        Labels.VOID,
        false,
        ctor.getAccessSpecifier().asString(),
        ctor.getBegin().map(p -> p.line).orElse(0),
        ctor.getEnd().map(p -> p.line).orElse(0),
        false,
        JAVA_LANGUAGE,
        Params.METHOD,
        ownerKind);
  }

  private void collectRecordCanonicalConstructor(
      String fqn, RecordDeclaration decl, JavaMemberWrites writes) {
    if (!JavaTypeNames.hasExplicitCanonicalConstructor(fqn, decl)) {
      writes
          .methods()
          .add(
              new Method(
                  fqn,
                  JavaTypeNames.buildRecordCanonicalConstructorSignature(fqn, decl),
                  Labels.INIT,
                  Labels.VOID,
                  false,
                  decl.getAccessSpecifier().asString(),
                  0,
                  0,
                  true,
                  JAVA_LANGUAGE,
                  Params.METHOD,
                  Labels.CLASS));
    }
  }

  private void collectImplicitDefaultConstructor(
      String fqn, ClassOrInterfaceDeclaration decl, JavaMemberWrites writes) {
    writes
        .methods()
        .add(
            new Method(
                fqn,
                fqn + Const.Symbols.DOT + Labels.INIT + Const.Symbols.PARENS,
                Labels.INIT,
                Labels.VOID,
                false,
                decl.getAccessSpecifier().asString(),
                0,
                0,
                true,
                JAVA_LANGUAGE,
                Params.METHOD,
                Labels.CLASS));
  }

  private void collectRecordAccessors(String fqn, RecordDeclaration decl, JavaMemberWrites writes) {
    var explicitMethods =
        decl.getMethods().stream()
            .filter(m -> m.getParameters().isEmpty())
            .map(MethodDeclaration::getNameAsString)
            .collect(Collectors.toSet());

    for (Parameter param : decl.getParameters()) {
      String accessorName = param.getNameAsString();
      if (!explicitMethods.contains(accessorName)) {
        String sig = fqn + Const.Symbols.DOT + accessorName + Const.Symbols.PARENS;
        writes
            .methods()
            .add(
                new Method(
                    fqn,
                    sig,
                    accessorName,
                    JavaTypeNames.resolveType(param.getType()),
                    false,
                    Params.PUBLIC,
                    0,
                    0,
                    true,
                    JAVA_LANGUAGE,
                    Params.METHOD,
                    Labels.CLASS));
      }
    }
  }

  private void upsertJavaMembers(Path file, JavaMemberWrites writes) {
    upsertFieldNodes(file, writes.fields());
    upsertMethodNodes(file, writes.methods());
    upsertAnnotationReferencesByFqn(writes.fqnAnnotations());
    upsertAnnotationReferencesBySig(writes.sigAnnotations());
  }

  private static List<AnnotationWrite> annotationWrites(
      String paramValue, NodeWithAnnotations<?> node, String ownerKind) {
    return node.getAnnotations().stream()
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
                  Params.ANNOTATION,
                  ownerKind);
            })
        .toList();
  }

  private static final class JavaMemberWrites {
    private final List<FieldWrite> fields = new ArrayList<>();
    private final List<Method> methods = new ArrayList<>();
    private final List<AnnotationWrite> fqnAnnotations = new ArrayList<>();
    private final List<AnnotationWrite> sigAnnotations = new ArrayList<>();

    List<FieldWrite> fields() {
      return fields;
    }

    List<Method> methods() {
      return methods;
    }

    List<AnnotationWrite> fqnAnnotations() {
      return fqnAnnotations;
    }

    List<AnnotationWrite> sigAnnotations() {
      return sigAnnotations;
    }
  }
}
