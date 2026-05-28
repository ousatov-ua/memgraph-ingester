package io.github.ousatov.tools.memgraph.exe.rag;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.exe.writer.GraphWrite.MemoryChunkWrite;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Builds derived {@code :MemoryChunk} rows from canonical Memory records.
 *
 * @author Oleksii Usatov
 */
public final class MemoryChunkBuilder {

  /** Canonical Memory node data needed to build one derived chunk. */
  public record MemorySource(
      String existingChunkId,
      String sourceLabel,
      String sourceId,
      String title,
      String topic,
      String status,
      String severity,
      String type,
      String priority,
      String source,
      String number,
      String rationale,
      String consequences,
      String content,
      String description,
      String summary,
      String evidence,
      String mitigation,
      String answer,
      String notes,
      String context,
      String decision,
      List<String> codeRefs) {}

  /** Builds one upsert payload for the supplied source memory. */
  public MemoryChunkWrite build(MemorySource source) {
    String text = text(source);
    return new MemoryChunkWrite(
        chunkId(source), source.sourceLabel(), source.sourceId(), text, sha256(text));
  }

  private static String chunkId(MemorySource source) {
    String existing = Objects.toString(source.existingChunkId(), Const.Symbols.EMPTY).strip();
    return existing.isBlank()
        ? "MCH-" + source.sourceLabel() + Const.Symbols.DASH + source.sourceId()
        : existing;
  }

  private static String text(MemorySource source) {
    StringBuilder builder = new StringBuilder();
    builder.append(source.sourceLabel()).append(Const.Symbols.COLON_SPACE);
    builder.append(blankToDefault(source.title(), source.sourceId())).append('\n');
    appendField(builder, "ID", source.sourceId());
    appendField(builder, "Topic", source.topic());
    appendField(builder, "Status", source.status());
    appendField(builder, "Severity", source.severity());
    appendField(builder, "Type", source.type());
    appendField(builder, "Priority", source.priority());
    appendField(builder, "Source", source.source());
    appendField(builder, "Number", source.number());
    appendBlock(builder, "Rationale", source.rationale());
    appendBlock(builder, "Consequences", source.consequences());
    appendBlock(builder, "Content", source.content());
    appendBlock(builder, "Description", source.description());
    appendBlock(builder, "Summary", source.summary());
    appendBlock(builder, "Evidence", source.evidence());
    appendBlock(builder, "Mitigation", source.mitigation());
    appendBlock(builder, "Answer", source.answer());
    appendBlock(builder, "Notes", source.notes());
    appendBlock(builder, "Context", source.context());
    appendBlock(builder, "Decision", source.decision());
    appendCodeRefs(builder, source.codeRefs());
    return builder.toString().strip();
  }

  private static void appendCodeRefs(StringBuilder builder, List<String> codeRefs) {
    List<String> refs =
        codeRefs == null
            ? List.of()
            : codeRefs.stream().filter(ref -> ref != null && !ref.isBlank()).sorted().toList();
    if (!refs.isEmpty()) {
      builder
          .append("CodeRefs: ")
          .append(String.join(Const.Symbols.COMMA_SPACE, refs))
          .append('\n');
    }
  }

  private static void appendField(StringBuilder builder, String label, String value) {
    append(builder, label, value);
  }

  private static void appendBlock(StringBuilder builder, String label, String value) {
    append(builder, label, value);
  }

  private static void append(StringBuilder builder, String label, String value) {
    String cleaned = Objects.toString(value, Const.Symbols.EMPTY).strip();
    if (!cleaned.isBlank()) {
      builder.append(label).append(Const.Symbols.COLON_SPACE).append(cleaned).append('\n');
    }
  }

  private static String blankToDefault(String value, String fallback) {
    String cleaned = Objects.toString(value, Const.Symbols.EMPTY).strip();
    return cleaned.isBlank() ? fallback : cleaned;
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
