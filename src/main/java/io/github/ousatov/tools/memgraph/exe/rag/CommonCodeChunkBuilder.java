package io.github.ousatov.tools.memgraph.exe.rag;

import com.github.javaparser.ast.Node;
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

  private static final int MAX_EXCERPT_LINES = 80;
  private static final int DOC_LOOKBACK_LINES = 16;
  private static final int ID_HASH_LENGTH = 16;

  private final CodeChunkAnalyzer<T> analyzer;

  protected CommonCodeChunkBuilder(CodeChunkAnalyzer<T> analyzer) {
    this.analyzer = analyzer;
  }

  /** Builds all derived chunks for one parsed source file. */
  public final List<CodeChunkWrite> build(Path file, T parsed) {
    CodeChunkAnalysis analysis = analyzer.analyze(parsed);
    List<String> lines = readLines(file);
    List<CodeChunkWrite> chunks = new ArrayList<>();
    addFileChunk(chunks, file, analysis.language(), lines);
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
      List<CodeChunkWrite> chunks, Path file, String language, List<String> lines) {
    chunks.add(
        chunk(
            "File",
            file.toString(),
            language,
            file,
            Const.Symbols.EMPTY,
            Const.Symbols.EMPTY,
            file.getFileName() == null ? file.toString() : file.getFileName().toString(),
            "file",
            1,
            lines.size(),
            lines));
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
            lines));
  }

  private static boolean methodLike(String memberType) {
    return Params.METHOD.equals(memberType)
        || Params.CONSTRUCTOR.equals(memberType)
        || Params.FUNCTION.equals(memberType)
        || Params.MODULE.equals(memberType);
  }

  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
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
            lines);
    return new CodeChunkWrite(
        id(sourceLabel, sourceId),
        sourceLabel,
        sourceId,
        language,
        file.toString(),
        ownerFqn,
        signature,
        text,
        sha256(text));
  }

  protected final int beginLine(Node node) {
    return node.getBegin().map(position -> position.line).orElse(0);
  }

  protected final int endLine(Node node) {
    return node.getEnd().map(position -> position.line).orElse(0);
  }

  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
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
      List<String> lines) {
    StringBuilder builder = new StringBuilder();
    builder.append("Language: ").append(language).append('\n');
    builder.append("Path: ").append(file).append('\n');
    builder.append("Source: ").append(sourceLabel).append(' ').append(sourceId).append('\n');
    appendField(builder, "Name", name);
    appendField(builder, "Kind", kind);
    appendField(builder, "Owner", ownerFqn);
    appendField(builder, "Signature", signature);
    builder.append("Source excerpt:\n").append(excerpt(lines, startLine, endLine)).append('\n');
    return builder.toString().strip();
  }

  private static String excerpt(List<String> lines, int startLine, int endLine) {
    if (lines.isEmpty()) {
      return Const.Symbols.EMPTY;
    }
    int safeStart = startLine <= 0 ? 1 : Math.min(startLine, lines.size());
    int safeEnd = endLine <= 0 ? safeStart : Math.max(safeStart, Math.min(endLine, lines.size()));
    int docStart = documentationStart(lines, safeStart);
    int limitedEnd = Math.min(safeEnd, docStart + MAX_EXCERPT_LINES - 1);
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
    int lowerBound = Math.max(0, previous - DOC_LOOKBACK_LINES);
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
    int lowerBound = Math.max(0, previous - DOC_LOOKBACK_LINES);
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
      MessageDigest digest = MessageDigest.getInstance(Const.SystemParams.SHA_256);
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
