package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.config.AppConfig;
import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.vo.rag.CodeChunkAnalysis;
import io.github.ousatov.tools.memgraph.vo.rag.MemberChunk;
import io.github.ousatov.tools.memgraph.vo.rag.TypeChunk;
import io.github.ousatov.tools.memgraph.vo.writer.CodeChunkWrite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Shared mechanics for building derived {@code :CodeChunk} rows.
 *
 * <p>Language-specific builders own parser details. This base class only handles stable chunk IDs,
 * text hashing, source excerpts, and documentation-comment lookback.
 *
 * @param <T> parser-owned source model
 * @author Oleksii Usatov
 */
public abstract class CommonCodeChunkBuilder<T> {

  private static final int MAX_EXCERPT_LINES = AppConfig.intValue("rag.max-excerpt-lines");
  private static final int DOC_LOOKBACK_LINES = AppConfig.intValue("rag.doc-lookback-lines");
  private static final int ID_HASH_LENGTH = 16;
  private static final String RAG_ROLE_FILE = "file";
  private static final String RAG_ROLE_PRIMARY = "primary";
  private static final String RAG_ROLE_SECONDARY = "secondary";
  private static final String RAG_ROLE_SYNTHETIC = "synthetic";
  private static final ThreadLocal<MessageDigest> SHA_256 =
      ThreadLocal.withInitial(CommonCodeChunkBuilder::newSha256);

  private final CodeChunkAnalyzer<T> analyzer;

  protected CommonCodeChunkBuilder(CodeChunkAnalyzer<T> analyzer) {
    this.analyzer = analyzer;
  }

  /** Builds all derived chunks for one parsed source file. */
  public final List<CodeChunkWrite> build(Path file, T parsed) {
    CodeChunkAnalysis analysis = analyzer.analyze(parsed);
    List<String> lines = readLines(file);
    List<CodeChunkWrite> chunks = new ArrayList<>();
    addFileChunk(chunks, file, analysis, lines);
    if (!analysis.moduleFqn().isBlank()) {
      chunks.add(
          chunk(
              Const.Labels.CLASS,
              analysis.moduleFqn(),
              analysis.language(),
              file,
              analysis.moduleFqn(),
              Const.Symbols.EMPTY,
              analysis.moduleName(),
              Params.MODULE,
              analysis.startLine(),
              analysis.endLine(),
              RAG_ROLE_SYNTHETIC,
              true,
              Const.Symbols.EMPTY,
              lines));
    }
    analysis.types().forEach(type -> addType(chunks, file, lines, analysis.language(), type));
    analysis
        .members()
        .forEach(member -> addMember(chunks, file, lines, analysis.language(), member));
    return List.copyOf(chunks);
  }

  protected final void addType(
      List<CodeChunkWrite> chunks, Path file, List<String> lines, String language, TypeChunk type) {
    chunks.add(
        chunk(
            type.sourceLabel(),
            type.sourceId(),
            language,
            file,
            type.ownerFqn(),
            Const.Symbols.EMPTY,
            type.name(),
            type.kind(),
            type.startLine(),
            type.endLine(),
            RAG_ROLE_PRIMARY,
            false,
            Const.Symbols.EMPTY,
            lines));
  }

  protected final void addMember(
      List<CodeChunkWrite> chunks,
      Path file,
      List<String> lines,
      String language,
      MemberChunk member) {
    addMember(
        chunks,
        file,
        lines,
        language,
        member.ownerFqn(),
        member.memberType(),
        member.kind(),
        member.key(),
        member.name(),
        member.startLine(),
        member.endLine());
  }

  protected final List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException _) {
      return List.of();
    }
  }

  protected final void addFileChunk(
      List<CodeChunkWrite> chunks, Path file, CodeChunkAnalysis analysis, List<String> lines) {
    chunks.add(
        chunk(
            "File",
            file.toString(),
            analysis.language(),
            file,
            Const.Symbols.EMPTY,
            Const.Symbols.EMPTY,
            file.getFileName() == null ? file.toString() : file.getFileName().toString(),
            "file",
            1,
            lines.size(),
            RAG_ROLE_FILE,
            false,
            buildDefinesSummary(analysis),
            lines));
  }

  private static String buildDefinesSummary(CodeChunkAnalysis analysis) {
    List<String> typeParts =
        analysis.types().stream().map(t -> t.name() + " (" + t.kind() + ")").toList();
    List<String> allMethodNames =
        analysis.members().stream()
            .filter(
                m -> Params.METHOD.equals(m.memberType()) || Params.FUNCTION.equals(m.memberType()))
            .map(MemberChunk::name)
            .distinct()
            .toList();
    List<String> methodSample =
        allMethodNames.size() > 10 ? allMethodNames.subList(0, 10) : allMethodNames;
    if (typeParts.isEmpty() && methodSample.isEmpty()) {
      return Const.Symbols.EMPTY;
    }
    StringBuilder sb = new StringBuilder();
    if (!typeParts.isEmpty()) {
      sb.append(String.join(", ", typeParts));
    }
    if (!methodSample.isEmpty()) {
      if (!sb.isEmpty()) {
        sb.append("; ");
      }
      sb.append("methods: ").append(String.join(", ", methodSample));
      int extra = allMethodNames.size() - methodSample.size();
      if (extra > 0) {
        sb.append(" (+").append(extra).append(" more)");
      }
    }
    return sb.toString();
  }

  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
  protected final void addMember(
      List<CodeChunkWrite> chunks,
      Path file,
      List<String> lines,
      String language,
      String ownerFqn,
      String memberType,
      String kind,
      String key,
      String name,
      int startLine,
      int endLine) {
    boolean method = methodLike(memberType);
    boolean synthetic = startLine <= 0 || endLine <= 0;
    String ragRole = memberRagRole(method, synthetic);
    chunks.add(
        chunk(
            method ? "Method" : "Field",
            key,
            language,
            file,
            ownerFqn,
            method ? key : Const.Symbols.EMPTY,
            name,
            kind,
            startLine,
            endLine,
            ragRole,
            synthetic,
            Const.Symbols.EMPTY,
            lines));
  }

  private static String memberRagRole(boolean method, boolean synthetic) {
    if (synthetic) {
      return RAG_ROLE_SYNTHETIC;
    }
    return method ? RAG_ROLE_PRIMARY : RAG_ROLE_SECONDARY;
  }

  private static boolean methodLike(String memberType) {
    return Params.METHOD.equals(memberType)
        || Params.CONSTRUCTOR.equals(memberType)
        || Params.FUNCTION.equals(memberType)
        || Params.MODULE.equals(memberType);
  }

  @SuppressWarnings("java:S107")
  protected final CodeChunkWrite chunk(
      String sourceLabel,
      String sourceId,
      String language,
      Path file,
      String ownerFqn,
      String signature,
      String name,
      String kind,
      int startLine,
      int endLine,
      String ragRole,
      boolean synthetic,
      String defines,
      List<String> lines) {
    String text =
        text(
            language,
            file,
            sourceLabel,
            sourceId,
            ownerFqn,
            signature,
            name,
            kind,
            startLine,
            endLine,
            defines,
            lines);
    return new CodeChunkWrite(
        id(sourceLabel, sourceId),
        sourceLabel,
        sourceId,
        language,
        file.toString(),
        ownerFqn,
        signature,
        name,
        kind,
        ragRole,
        startLine,
        endLine,
        synthetic,
        text,
        sha256(text));
  }

  @SuppressWarnings("java:S107")
  private static String text(
      String language,
      Path file,
      String sourceLabel,
      String sourceId,
      String ownerFqn,
      String signature,
      String name,
      String kind,
      int startLine,
      int endLine,
      String defines,
      List<String> lines) {
    StringBuilder builder = new StringBuilder();
    builder.append("Language: ").append(language).append('\n');
    builder.append("Path: ").append(file).append('\n');
    builder.append("Source: ").append(sourceLabel).append(' ').append(sourceId).append('\n');
    appendField(builder, "Name", name);
    appendField(builder, "Kind", kind);
    if (!sourceId.equals(ownerFqn)) {
      appendField(builder, "Owner", ownerFqn);
    }
    if (!sourceId.equals(signature)) {
      appendField(builder, "Signature", signature);
    }
    appendField(builder, "Defines", defines);
    builder.append("Source excerpt:\n").append(excerpt(lines, startLine, endLine)).append('\n');
    return builder.toString().strip();
  }

  private static String excerpt(List<String> lines, int startLine, int endLine) {
    if (lines.isEmpty()) {
      return Const.Symbols.EMPTY;
    }
    int safeStart = startLine <= 0 ? 1 : Math.clamp(startLine, 1, lines.size());
    int safeEnd = endLine <= 0 ? safeStart : Math.clamp(endLine, safeStart, lines.size());
    int docStart = documentationStart(lines, safeStart);
    int limitedEnd = Math.clamp(docStart + MAX_EXCERPT_LINES - 1, docStart, safeEnd);
    return String.join(Const.Symbols.NEW_LINE, lines.subList(docStart - 1, limitedEnd));
  }

  private static int documentationStart(List<String> lines, int startLine) {
    int previous = startLine - 2;
    while (previous >= 0 && lines.get(previous).isBlank()) {
      previous--;
    }
    if (previous < 0) {
      return startLine;
    }

    String trimmed = lines.get(previous).trim();
    if (trimmed.endsWith("*/")) {
      return blockCommentStart(lines, previous, startLine);
    }
    if (isLineDocComment(trimmed)) {
      return lineCommentStart(lines, previous);
    }
    return startLine;
  }

  private static int blockCommentStart(List<String> lines, int previous, int fallbackStartLine) {
    int lowerBound = Math.clamp(previous - DOC_LOOKBACK_LINES, 0, previous);
    for (int i = previous; i >= lowerBound; i--) {
      String trimmed = lines.get(i).trim();
      if (trimmed.startsWith("/*")) {
        return i + 1;
      }
    }
    return fallbackStartLine;
  }

  private static int lineCommentStart(List<String> lines, int previous) {
    int current = previous;
    int lowerBound = Math.clamp(previous - DOC_LOOKBACK_LINES, 0, previous);
    while (current >= lowerBound && isLineDocComment(lines.get(current).trim())) {
      current--;
    }
    return current + 2;
  }

  private static boolean isLineDocComment(String trimmed) {
    return trimmed.startsWith("///")
        || trimmed.startsWith(Const.Symbols.DOUBLE_SLASH)
        || trimmed.startsWith(Const.Symbols.HASH);
  }

  private static void appendField(StringBuilder builder, String label, String value) {
    if (value != null && !value.isBlank()) {
      builder.append(label).append(Const.Symbols.COLON_SPACE).append(value).append('\n');
    }
  }

  private static String id(String sourceLabel, String sourceId) {
    return "CCH-"
        + sourceLabel.toLowerCase(Locale.ROOT)
        + Const.Symbols.DASH
        + sha256(sourceId).substring(0, ID_HASH_LENGTH);
  }

  private static String sha256(String text) {
    try {
      MessageDigest digest = SHA_256.get();
      digest.reset();
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } finally {
      SHA_256.remove();
    }
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance(Const.SystemParams.SHA_256);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
