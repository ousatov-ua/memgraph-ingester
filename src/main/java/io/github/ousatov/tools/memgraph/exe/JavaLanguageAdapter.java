package io.github.ousatov.tools.memgraph.exe;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JavaParser-backed adapter for the existing Java ingestion behavior. */
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
  public boolean ingestFile(GraphWriter writer, Path file) {
    var cuOpt = parseService.parse(file);
    if (cuOpt.isEmpty()) {
      return false;
    }
    CompilationUnit cu = cuOpt.get();
    String pkg = cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
    try {
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
    } catch (Exception e) {
      log.warn("Failed to ingest {}: {}", file, e.getMessage());
      return false;
    }
  }
}
