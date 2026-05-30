package io.github.ousatov.tools.memgraph.exe.adapter;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Labels;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.rag.CtagsCodeChunkBuilder;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWriter;
import io.github.ousatov.tools.memgraph.exe.writer.ctags.CtagsGraphWriter;
import io.github.ousatov.tools.memgraph.vo.Method;
import io.github.ousatov.tools.memgraph.vo.adapter.DetectedLanguage;
import io.github.ousatov.tools.memgraph.vo.adapter.FileFingerprint;
import io.github.ousatov.tools.memgraph.vo.adapter.SourceFileDefinitions;
import io.github.ousatov.tools.memgraph.vo.analysis.CtagsAnalysis;
import io.github.ousatov.tools.memgraph.vo.writer.FieldWrite;
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
      Set.of(
          Const.Files.JAVA_EXTENSION,
          Const.Files.JAVASCRIPT_EXTENSION,
          Const.Files.JSX_EXTENSION,
          Const.Files.TYPESCRIPT_EXTENSION,
          Const.Files.TSX_EXTENSION,
          Const.Files.TYPESCRIPT_MODULE_EXTENSION,
          Const.Files.TYPESCRIPT_COMMONJS_EXTENSION,
          Const.Files.JAVASCRIPT_MODULE_EXTENSION,
          Const.Files.COMMONJS_EXTENSION,
          Const.Files.PYTHON_EXTENSION,
          Const.Files.PYTHON_STUB_EXTENSION);
  private static final Set<String> NON_CODE_EXTENSIONS =
      Set.of(
          ".adoc",
          ".asciidoc",
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
          ".less",
          ".lock",
          ".md",
          ".plist",
          ".properties",
          ".rst",
          ".sass",
          ".scss",
          ".styl",
          ".stylus",
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
          "asciidoc",
          "css",
          "html",
          "ini",
          "json",
          "less",
          "markdown",
          "rest",
          "rst",
          "sass",
          "scss",
          "styl",
          "stylus",
          Const.Params.TEXT,
          "toml",
          "xml",
          "yaml");
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of(
          ".git",
          ".hg",
          ".svn",
          Const.Files.NODE_MODULES,
          Const.Files.PYCACHE,
          Const.Files.VIRTUAL_ENV,
          Const.Files.VENV,
          Const.Files.TOX,
          Const.Files.NOX,
          Const.Files.SITE_PACKAGES,
          Const.Files.BUILD,
          Const.Files.DIST,
          Const.Files.TARGET,
          Const.Files.OUT,
          "vendor");

  private final Path sourceRoot;
  private final CtagsAnalyzer analyzer;
  private final CtagsCodeChunkBuilder codeChunks = new CtagsCodeChunkBuilder();
  private final Map<Path, DetectedLanguage> detectedLanguages = new ConcurrentHashMap<>();

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
    methodSignatures.add(
        analysis.moduleFqn() + Const.Symbols.DOT + Labels.INIT + Const.Symbols.PARENS);
    for (io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl type : analysis.types()) {
      if (type.interfaceLike()) {
        interfaceFqns.add(type.fqn());
      } else {
        classFqns.add(type.fqn());
        if (hasSyntheticConstructor(type)) {
          methodSignatures.add(type.fqn() + Const.Symbols.DOT + Labels.INIT + Const.Symbols.PARENS);
        }
      }
    }
    for (io.github.ousatov.tools.memgraph.vo.analysis.ctags.MemberDecl member :
        analysis.members()) {
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
                      type.rawKind(),
                      type.interfaceLike(),
                      type.startLine(),
                      type.endLine()));
      upsertMembers(ctagsWriter, file, analysis.language(), analysis.members());
      writer.replaceCodeChunksForFile(file, codeChunks.build(file, analysis));
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
    return LanguageAdapter.shouldVisitSourceDirectory(directory)
        && !isInSkippedDirectory(directory);
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
      throw e;
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
      Collection<io.github.ousatov.tools.memgraph.vo.analysis.ctags.MemberDecl> members) {
    List<FieldWrite> fields = new ArrayList<>();
    List<Method> methods = new ArrayList<>();
    for (io.github.ousatov.tools.memgraph.vo.analysis.ctags.MemberDecl member : members) {
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
    Optional<FileFingerprint> fingerprint = FileFingerprint.read(absoluteFile);
    if (fingerprint.isEmpty()) {
      detectedLanguages.remove(absoluteFile);
      return Optional.empty();
    }
    DetectedLanguage cached = detectedLanguages.get(absoluteFile);
    if (cached != null && cached.matches(fingerprint.get())) {
      return Optional.of(cached.language());
    }
    try {
      Optional<SourceLanguage> detected = analyzer.detectLanguage(absoluteFile);
      detected.ifPresentOrElse(
          language ->
              detectedLanguages.put(
                  absoluteFile, new DetectedLanguage(language, fingerprint.get())),
          () -> detectedLanguages.remove(absoluteFile));
      return detected;
    } catch (RuntimeException e) {
      detectedLanguages.remove(absoluteFile);
      throw e;
    }
  }

  private Path absolute(Path file) {
    Path normalizedFile = file.normalize();
    if (normalizedFile.isAbsolute()) {
      return normalizedFile;
    }
    Path rootLocalFile = sourceRoot.resolve(normalizedFile).normalize();
    if (Files.exists(rootLocalFile)) {
      return rootLocalFile;
    }
    if (normalizedFile.startsWith(sourceRoot.normalize())) {
      return normalizedFile;
    }
    return rootLocalFile;
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

  private static boolean hasSyntheticConstructor(
      io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl type) {
    String rawKind =
        type.rawKind() == null ? Const.Symbols.EMPTY : type.rawKind().toLowerCase(Locale.ROOT);
    return Params.CLASS.equals(type.graphKind()) && Params.CLASS.equals(rawKind);
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
