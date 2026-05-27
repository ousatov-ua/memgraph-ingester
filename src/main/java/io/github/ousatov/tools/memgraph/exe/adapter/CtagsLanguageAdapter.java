package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalysis;
import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.FieldWrite;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.ctags.CtagsGraphWriter;
import io.github.ousatov.tools.memgraph.vo.Method;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Universal Ctags-backed fallback adapter for detected source languages.
 *
 * @author Oleksii Usatov
 */
public final class CtagsLanguageAdapter implements LanguageAdapter<CtagsAnalysis> {

  private static final Logger log = LoggerFactory.getLogger(CtagsLanguageAdapter.class);
  private static final Set<String> FIRST_CLASS_EXTENSIONS =
      Set.of(".java", ".js", ".jsx", ".ts", ".tsx", ".mts", ".cts", ".mjs", ".cjs", ".py", ".pyi");
  private static final Set<String> NON_CODE_EXTENSIONS =
      Set.of(
          ".cfg",
          ".conf",
          ".csv",
          ".css",
          ".env",
          ".gitattributes",
          ".gitignore",
          ".htm",
          ".html",
          ".ini",
          ".json",
          ".jsonl",
          ".lock",
          ".md",
          ".plist",
          ".properties",
          ".rst",
          ".svg",
          ".text",
          ".toml",
          ".tsv",
          ".txt",
          ".xhtml",
          ".xml",
          ".xsd",
          ".xsl",
          ".xslt",
          ".yaml",
          ".yml");
  private static final Set<String> NON_CODE_LANGUAGE_GRAPH_NAMES =
      Set.of(
          "css", "html", "ini", "json", "markdown", "rest", "rst", "text", "toml", "xml", "yaml");
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of(
          ".git",
          ".hg",
          ".svn",
          "node_modules",
          "__pycache__",
          ".venv",
          "venv",
          ".tox",
          ".nox",
          "site-packages",
          "build",
          "dist",
          "target",
          "out",
          "vendor");

  private final Path sourceRoot;
  private final CtagsAnalyzer analyzer;
  private final Map<Path, SourceLanguage> detectedLanguages = new ConcurrentHashMap<>();

  public CtagsLanguageAdapter(Path sourceRoot, CtagsAnalyzer analyzer) {
    this.sourceRoot = sourceRoot;
    this.analyzer = analyzer;
  }

  @Override
  public SourceLanguage language() {
    throw new UnsupportedOperationException("Ctags adapter detects source language per file");
  }

  @Override
  public Optional<SourceLanguage> staticLanguage() {
    return Optional.empty();
  }

  @Override
  public SourceLanguage language(Path file) {
    return detectedLanguage(file)
        .orElseThrow(() -> new ProcessingException("Ctags language was not detected for " + file));
  }

  @Override
  public boolean accepts(Path file) {
    Path localFile = sourceRootLocal(file);
    if (isFirstClassSource(localFile)
        || isNonCodeSource(localFile)
        || isInSkippedDirectory(localFile)) {
      return false;
    }
    return detectedLanguage(file)
        .filter(CtagsLanguageAdapter::isFallbackProgrammingLanguage)
        .isPresent();
  }

  @Override
  public boolean acceptsDeletedPath(Path file) {
    Path localFile = sourceRootLocal(file);
    return !isFirstClassSource(localFile)
        && !isNonCodeSource(localFile)
        && !isInSkippedDirectory(localFile);
  }

  @Override
  public Optional<CtagsAnalysis> parse(Path file) {
    if (!accepts(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(analyzer.analyze(absolute(file)));
    } catch (RuntimeException e) {
      log.warn("Failed to parse {} with ctags: {}", file, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public SourceFileDefinitions collectDefinitions(CtagsAnalysis analysis) {
    Set<String> classFqns = new LinkedHashSet<>();
    Set<String> interfaceFqns = new LinkedHashSet<>();
    Set<String> methodSignatures = new LinkedHashSet<>();
    Set<String> fieldFqns = new LinkedHashSet<>();
    classFqns.add(analysis.moduleFqn());
    methodSignatures.add(analysis.moduleFqn() + "." + Labels.INIT + "()");
    for (CtagsAnalysis.TypeDecl type : analysis.types()) {
      if (type.interfaceLike()) {
        interfaceFqns.add(type.fqn());
      } else {
        classFqns.add(type.fqn());
        if (Params.CLASS.equals(type.graphKind())) {
          methodSignatures.add(type.fqn() + "." + Labels.INIT + "()");
        }
      }
    }
    for (CtagsAnalysis.MemberDecl member : analysis.members()) {
      if (Params.METHOD.equals(member.memberType())) {
        methodSignatures.add(member.fqnOrSignature());
      } else {
        fieldFqns.add(member.fqnOrSignature());
      }
    }
    return SourceFileDefinitions.of(
        classFqns, interfaceFqns, Set.of(), methodSignatures, fieldFqns);
  }

  @Override
  public boolean write(GraphWriter writer, Path file, CtagsAnalysis analysis) {
    CtagsGraphWriter ctagsWriter = new CtagsGraphWriter(writer.dependencies());
    try {
      writer.upsertProject(sourceRoot, List.of(analysis.language()));
      writer.upsertFile(file, analysis.language());
      writer.upsertPackage(analysis.packageName(), analysis.language());
      ctagsWriter.upsertModule(
          file,
          analysis.language(),
          analysis.packageName(),
          analysis.moduleFqn(),
          analysis.moduleName(),
          analysis.modulePath(),
          analysis.startLine(),
          analysis.endLine());
      analysis
          .types()
          .forEach(
              type ->
                  ctagsWriter.upsertType(
                      file,
                      analysis.language(),
                      analysis.packageName(),
                      type.fqn(),
                      type.name(),
                      type.graphKind(),
                      type.interfaceLike(),
                      type.startLine(),
                      type.endLine()));
      upsertMembers(ctagsWriter, file, analysis.language(), analysis.members());
      return true;
    } catch (RuntimeException e) {
      if (GraphWriter.isRetryable(e)) {
        throw e;
      }
      log.warn("Failed to ingest {} with ctags: {}", file, e.getMessage());
      return false;
    }
  }

  @Override
  public LanguageAdapter<CtagsAnalysis> forSourceRoot(Path newSourceRoot) {
    return new CtagsLanguageAdapter(newSourceRoot, analyzer.withSourceRoot(newSourceRoot));
  }

  @Override
  public boolean shouldVisitDirectory(Path directory) {
    return !isInSkippedDirectory(directory);
  }

  @Override
  public boolean usesCustomFileDiscovery() {
    return true;
  }

  @Override
  public List<Path> discoverFiles(Path root) {
    List<Path> files = new ArrayList<>();
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public @NonNull FileVisitResult preVisitDirectory(
                @NonNull Path dir, @NonNull BasicFileAttributes attrs) {
              return shouldVisitDirectory(LanguageAdapter.localPath(root, dir))
                  ? FileVisitResult.CONTINUE
                  : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public @NonNull FileVisitResult visitFile(
                @NonNull Path file, @NonNull BasicFileAttributes attrs) {
              if (attrs.isRegularFile() && accepts(LanguageAdapter.localPath(root, file))) {
                files.add(file);
              }
              return FileVisitResult.CONTINUE;
            }
          });
      files.sort(Comparator.naturalOrder());
      return List.copyOf(files);
    } catch (ProcessingException e) {
      log.warn("Ctags fallback discovery is unavailable: {}", e.getMessage());
      return List.of();
    } catch (IOException e) {
      throw new ProcessingException("Cannot walk source root for ctags discovery", e);
    }
  }

  @Override
  public String displayName() {
    return "Ctags detected languages";
  }

  private void upsertMembers(
      CtagsGraphWriter writer,
      Path file,
      SourceLanguage language,
      Collection<CtagsAnalysis.MemberDecl> members) {
    List<FieldWrite> fields = new ArrayList<>();
    List<Method> methods = new ArrayList<>();
    for (CtagsAnalysis.MemberDecl member : members) {
      if (Params.METHOD.equals(member.memberType())) {
        methods.add(
            CtagsGraphWriter.method(
                language,
                member.ownerFqn(),
                member.fqnOrSignature(),
                member.name(),
                member.dataType(),
                member.isStatic(),
                member.visibility(),
                member.startLine(),
                member.endLine(),
                member.graphKind()));
      } else {
        fields.add(
            CtagsGraphWriter.field(
                language,
                member.ownerFqn(),
                member.fqnOrSignature(),
                member.name(),
                member.dataType(),
                member.isStatic(),
                member.visibility(),
                member.graphKind()));
      }
    }
    writer.upsertMembers(file, fields, methods);
  }

  private Optional<SourceLanguage> detectedLanguage(Path file) {
    Path absoluteFile = absolute(file);
    SourceLanguage cached = detectedLanguages.get(absoluteFile);
    if (cached != null) {
      return Optional.of(cached);
    }
    try {
      Optional<SourceLanguage> detected = analyzer.detectLanguage(absoluteFile);
      detected.ifPresent(language -> detectedLanguages.put(absoluteFile, language));
      return detected;
    } catch (RuntimeException e) {
      log.debug("Ctags could not detect language for {}: {}", file, e.getMessage());
      return Optional.empty();
    }
  }

  private Path absolute(Path file) {
    Path normalizedFile = file.normalize();
    if (normalizedFile.isAbsolute() || normalizedFile.startsWith(sourceRoot.normalize())) {
      return normalizedFile;
    }
    return sourceRoot.resolve(normalizedFile).normalize();
  }

  private Path sourceRootLocal(Path file) {
    Path normalizedFile = file.normalize();
    Path normalizedRoot = sourceRoot.normalize();
    if (normalizedFile.isAbsolute()) {
      Path absoluteRoot = sourceRoot.toAbsolutePath().normalize();
      if (normalizedFile.startsWith(absoluteRoot)) {
        return absoluteRoot.relativize(normalizedFile);
      }
      return normalizedFile;
    }
    if (normalizedFile.startsWith(normalizedRoot)) {
      return normalizedRoot.relativize(normalizedFile);
    }
    return normalizedFile;
  }

  private static boolean isFirstClassSource(Path file) {
    String lower = file.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    return FIRST_CLASS_EXTENSIONS.stream().anyMatch(lower::endsWith);
  }

  private static boolean isNonCodeSource(Path file) {
    String lower = file.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    return NON_CODE_EXTENSIONS.stream().anyMatch(lower::endsWith);
  }

  private static boolean isFallbackProgrammingLanguage(SourceLanguage language) {
    return !language.isFirstClass()
        && !NON_CODE_LANGUAGE_GRAPH_NAMES.contains(language.graphName());
  }

  private static boolean isInSkippedDirectory(Path path) {
    for (Path part : path) {
      if (SKIPPED_DIRECTORIES.contains(part.toString())) {
        return true;
      }
    }
    return false;
  }
}
