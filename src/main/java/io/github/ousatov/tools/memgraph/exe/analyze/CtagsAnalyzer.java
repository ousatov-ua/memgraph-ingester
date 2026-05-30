package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.vo.analysis.ctags.CtagsTag;
import io.github.ousatov.tools.memgraph.vo.analysis.ctags.ProcessResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Universal Ctags analyzer for fallback detected-language ingestion.
 *
 * @author Oleksii Usatov
 */
public final class CtagsAnalyzer {

  private static final Logger log = LoggerFactory.getLogger(CtagsAnalyzer.class);
  private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
  private static final Set<String> TYPE_KINDS =
      Set.of(
          "class",
          "struct",
          "enum",
          "namespace",
          "module",
          "object",
          "trait",
          "union",
          "record",
          "package");
  private static final Set<String> INTERFACE_KINDS =
      Set.of("interface", "protocol", Const.Params.TYPE, "typedef", "alias");
  private static final Set<String> METHOD_KINDS =
      Set.of(
          "function",
          "method",
          "procedure",
          "subroutine",
          "macro",
          "task",
          Const.Params.TARGET,
          "func",
          Const.Params.CONSTRUCTOR);
  private static final Set<String> FIELD_KINDS =
      Set.of(
          "variable",
          "constant",
          "field",
          Const.Params.PROPERTY,
          "member",
          "parameter",
          "key",
          "var",
          "local",
          "externvar");
  private static final Set<String> ENUM_MEMBER_KINDS =
      Set.of("enumerator", "enumconstant", "enum-member");

  private final Path sourceRoot;
  private final ManagedCtagsRuntime runtime;

  public CtagsAnalyzer(Path sourceRoot, ManagedCtagsRuntime runtime) {
    this.sourceRoot = sourceRoot;
    this.runtime = runtime;
  }

  /** Detects the ctags language for one file. */
  public Optional<SourceLanguage> detectLanguage(Path file) {
    ProcessResult result =
        runCtags(List.of(Const.Cli.CTAGS_OPTIONS_NONE, "--print-language", file.toString()));
    if (result.exitCode() != 0) {
      return Optional.empty();
    }
    return parseDetectedLanguage(result.stdout());
  }

  /** Analyzes one source file using the ctags JSON Lines output. */
  public CtagsAnalysis analyze(Path file) {
    SourceLanguage language =
        detectLanguage(file)
            .orElseThrow(
                () -> new ProcessingException("Ctags could not detect a language for " + file));
    ProcessResult result =
        runCtags(
            List.of(
                Const.Cli.CTAGS_OPTIONS_NONE,
                "--output-format=json",
                "--fields=+nKlsSe",
                "--extras=-F",
                "-f",
                Const.Symbols.DASH,
                file.toString()));
    if (result.exitCode() != 0) {
      throw new ProcessingException(
          "Ctags failed for " + file + Const.Symbols.COLON_SPACE + result.stderr().trim());
    }
    return analysisFromTags(file, language, parseTags(result.stdout()));
  }

  /** Returns an equivalent analyzer for another source root. */
  public CtagsAnalyzer withSourceRoot(Path newSourceRoot) {
    return new CtagsAnalyzer(newSourceRoot, runtime);
  }

  private CtagsAnalysis analysisFromTags(Path file, SourceLanguage language, List<CtagsTag> tags) {
    String modulePath = CtagsNames.modulePath(sourceRoot, file);
    String moduleFqn = CtagsNames.moduleFqn(language, sourceRoot, file);
    String moduleName =
        Optional.ofNullable(file.getFileName()).map(Path::toString).orElse(modulePath);
    String packageName = CtagsNames.packageName(language, sourceRoot, file);
    int endLine = fileEndLine(file, tags);
    List<io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl> types = new ArrayList<>();
    Map<String, String> typeFqnsByScope = new LinkedHashMap<>();
    List<CtagsTag> pendingTypeTags = new ArrayList<>();
    for (CtagsTag tag : tags) {
      String graphKind = graphKind(tag.kind());
      if (isTypeGraphKind(graphKind)) {
        pendingTypeTags.add(tag);
      }
    }
    while (!pendingTypeTags.isEmpty()) {
      boolean resolvedAny = false;
      for (int index = 0; index < pendingTypeTags.size(); ) {
        CtagsTag tag = pendingTypeTags.get(index);
        Optional<String> ownerFqn = resolvedOwnerFqn(moduleFqn, tag.scope(), typeFqnsByScope);
        if (ownerFqn.isEmpty()) {
          index++;
          continue;
        }
        addTypeDecl(types, typeFqnsByScope, tag, graphKind(tag.kind()), ownerFqn.get());
        pendingTypeTags.remove(index);
        resolvedAny = true;
      }
      if (!resolvedAny) {
        for (CtagsTag tag : pendingTypeTags) {
          addTypeDecl(types, typeFqnsByScope, tag, graphKind(tag.kind()), moduleFqn);
        }
        pendingTypeTags.clear();
      }
    }

    List<io.github.ousatov.tools.memgraph.vo.analysis.ctags.MemberDecl> members = new ArrayList<>();
    for (CtagsTag tag : tags) {
      String graphKind = graphKind(tag.kind());
      if (isTypeGraphKind(graphKind) || !isMemberGraphKind(graphKind)) {
        continue;
      }
      String ownerFqn = ownerFqn(moduleFqn, tag.scope(), typeFqnsByScope);
      String memberType = isMethodGraphKind(graphKind) ? Params.METHOD : Params.FIELD;
      String key =
          Params.METHOD.equals(memberType)
              ? CtagsNames.methodSignature(ownerFqn, tag.name(), tag.signature())
              : CtagsNames.childFqn(ownerFqn, tag.name());
      members.add(
          new io.github.ousatov.tools.memgraph.vo.analysis.ctags.MemberDecl(
              ownerFqn,
              memberType,
              graphKind,
              key,
              tag.name(),
              tag.typeref(),
              tag.isStatic(),
              tag.access(),
              tag.line(),
              tag.endLine()));
    }
    return new CtagsAnalysis(
        language, moduleFqn, moduleName, packageName, modulePath, 1, endLine, types, members);
  }

  private static void addTypeDecl(
      List<io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl> types,
      Map<String, String> typeFqnsByScope,
      CtagsTag tag,
      String graphKind,
      String ownerFqn) {
    String fqn = CtagsNames.childFqn(ownerFqn, tag.name());
    boolean interfaceLike = Params.INTERFACE.equals(graphKind);
    types.add(
        new io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl(
            graphKind,
            normalizeKind(tag.kind()),
            fqn,
            tag.name(),
            interfaceLike,
            tag.line(),
            tag.endLine()));
    typeFqnsByScope.putIfAbsent(tag.name(), fqn);
    typeFqnsByScope.put(scopeKey(tag.scope(), tag.name()), fqn);
  }

  private static String ownerFqn(
      String moduleFqn, String scope, Map<String, String> typeFqnsByScope) {
    if (scope == null || scope.isBlank()) {
      return moduleFqn;
    }
    String normalized = scope.replace(Const.Symbols.DOUBLE_COLON, Const.Symbols.DOT);
    String direct = typeFqnsByScope.get(normalized);
    if (direct != null) {
      return direct;
    }
    int dot = normalized.lastIndexOf('.');
    String simple = dot < 0 ? normalized : normalized.substring(dot + 1);
    return typeFqnsByScope.getOrDefault(simple, moduleFqn);
  }

  private static Optional<String> resolvedOwnerFqn(
      String moduleFqn, String scope, Map<String, String> typeFqnsByScope) {
    if (scope == null || scope.isBlank()) {
      return Optional.of(moduleFqn);
    }
    String normalized = scope.replace(Const.Symbols.DOUBLE_COLON, Const.Symbols.DOT);
    String direct = typeFqnsByScope.get(normalized);
    if (direct != null) {
      return Optional.of(direct);
    }
    int dot = normalized.lastIndexOf('.');
    String simple = dot < 0 ? normalized : normalized.substring(dot + 1);
    return Optional.ofNullable(typeFqnsByScope.get(simple));
  }

  private static String scopeKey(String scope, String name) {
    return scope == null || scope.isBlank()
        ? name
        : scope.replace(Const.Symbols.DOUBLE_COLON, Const.Symbols.DOT) + Const.Symbols.DOT + name;
  }

  private static String graphKind(String rawKind) {
    String kind = normalizeKind(rawKind);
    if (INTERFACE_KINDS.contains(kind)) {
      return Params.INTERFACE;
    }
    if (TYPE_KINDS.contains(kind)) {
      return kind.equals(Params.ENUM) ? Params.ENUM : Params.CLASS;
    }
    if (METHOD_KINDS.contains(kind)) {
      return kind.equals(Const.Params.CONSTRUCTOR) ? Params.CONSTRUCTOR : Params.METHOD;
    }
    if (ENUM_MEMBER_KINDS.contains(kind)) {
      return Params.ENUM_MEMBER;
    }
    if (FIELD_KINDS.contains(kind)) {
      return kind;
    }
    return Const.Symbols.EMPTY;
  }

  private static boolean isTypeGraphKind(String graphKind) {
    return Params.CLASS.equals(graphKind)
        || Params.INTERFACE.equals(graphKind)
        || Params.ENUM.equals(graphKind);
  }

  private static boolean isMemberGraphKind(String graphKind) {
    return isMethodGraphKind(graphKind)
        || Params.ENUM_MEMBER.equals(graphKind)
        || FIELD_KINDS.contains(graphKind);
  }

  private static boolean isMethodGraphKind(String graphKind) {
    return Params.METHOD.equals(graphKind) || Params.CONSTRUCTOR.equals(graphKind);
  }

  private static String normalizeKind(String rawKind) {
    if (rawKind == null || rawKind.isBlank()) {
      return Const.Symbols.EMPTY;
    }
    return rawKind.trim().toLowerCase(Locale.ROOT);
  }

  private static int fileEndLine(Path file, List<CtagsTag> tags) {
    int tagEndLine = tags.stream().mapToInt(CtagsTag::endLine).max().orElse(1);
    try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
      return Math.max(tagEndLine, (int) lines.count());
    } catch (IOException | UncheckedIOException _) {
      return tagEndLine;
    }
  }

  private ProcessResult runCtags(List<String> arguments) {
    List<String> command = new ArrayList<>();
    command.add(runtime.ctagsExecutable().toString());
    command.addAll(arguments);
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    try {
      Process process = processBuilder.start();
      CompletableFuture<String> stdout = readAsync(process.getInputStream());
      CompletableFuture<String> stderr = readAsync(process.getErrorStream());
      if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new ProcessingException("Ctags timed out");
      }
      return new ProcessResult(process.exitValue(), stdout.join(), stderr.join());
    } catch (IOException e) {
      throw new ProcessingException("Could not run Universal Ctags", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProcessingException("Interrupted while running Universal Ctags", e);
    }
  }

  private static CompletableFuture<String> readAsync(java.io.InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new ProcessingException("Could not read ctags output", e);
          }
        });
  }

  static Optional<SourceLanguage> parseDetectedLanguage(String stdout) {
    return stdout
        .lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .map(CtagsAnalyzer::languageNameFromLine)
        .filter(name -> !"none".equalsIgnoreCase(name))
        .filter(name -> !"unknown".equalsIgnoreCase(name))
        .findFirst()
        .map(SourceLanguage::fromCtagsName);
  }

  private static String languageNameFromLine(String line) {
    int colon = line.lastIndexOf(':');
    return colon < 0 ? line.trim() : line.substring(colon + 1).trim();
  }

  static List<CtagsTag> parseTags(String output) {
    List<CtagsTag> tags = new ArrayList<>();
    output
        .lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .forEach(
            line -> {
              try {
                Map<String, String> obj = FlatJsonObjectParser.parse(line);
                if (!Const.Params.TAG.equals(obj.getOrDefault("_type", Const.Params.TAG))) {
                  return;
                }
                String name = value(obj, "name");
                if (name.isBlank()) {
                  return;
                }
                int lineNumber = intValue(obj.get("line"), 1);
                int endLine = Math.max(lineNumber, intValue(obj.get("end"), lineNumber));
                tags.add(
                    new CtagsTag(
                        name,
                        value(obj, "kind"),
                        value(obj, "scope"),
                        value(obj, "scopeKind"),
                        value(obj, Const.Params.SIGNATURE),
                        value(obj, "typeref"),
                        value(obj, "access"),
                        booleanValue(obj.get("static")),
                        lineNumber,
                        endLine));
              } catch (RuntimeException e) {
                log.debug("Skipping malformed ctags JSON line: {}", e.getMessage());
              }
            });
    return List.copyOf(tags);
  }

  private static String value(Map<String, String> obj, String key) {
    String value = obj.get(key);
    return value == null || Const.SystemParams.NULL.equals(value) ? Const.Symbols.EMPTY : value;
  }

  private static int intValue(String raw, int defaultValue) {
    if (raw == null || raw.isBlank() || Const.SystemParams.NULL.equals(raw)) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException _) {
      return defaultValue;
    }
  }

  private static boolean booleanValue(String raw) {
    return Boolean.parseBoolean(raw);
  }
}
