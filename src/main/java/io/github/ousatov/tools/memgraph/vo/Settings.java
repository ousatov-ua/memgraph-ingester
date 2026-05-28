package io.github.ousatov.tools.memgraph.vo;

/**
 * Settings
 *
 * @param applySchema if true, applies schema first
 * @param wipeProjectCode if true, deletes this project's code graph before ingesting
 * @param wipeProjectMemories if true, deletes this project's memory graph before ingesting
 * @param wipeCodeRag if true, deletes this project's derived CodeChunk rows before ingesting
 * @param wipeMemoryRag if true, deletes this project's derived MemoryChunk rows before ingesting
 * @param incremental if true, skips files whose lastModified matches the stored value; silently
 *     disabled when {@code wipeAllData} or {@code wipeProjectCode} is set, because wiping removes
 *     all stored timestamps making incremental comparison impossible
 * @param codeEmbeddings Memgraph-managed code chunk embedding refresh settings
 * @param memoryEmbeddings Memgraph-managed memory chunk embedding refresh settings
 * @author Oleksii Usatov
 */
public record Settings(
    boolean wipeAllData,
    boolean applySchema,
    boolean wipeProjectCode,
    boolean wipeProjectMemories,
    boolean wipeCodeRag,
    boolean wipeMemoryRag,
    boolean incremental,
    boolean watch,
    EmbeddingSettings codeEmbeddings,
    EmbeddingSettings memoryEmbeddings) {

  public Settings(
      boolean wipeAllData,
      boolean applySchema,
      boolean wipeProjectCode,
      boolean wipeProjectMemories,
      boolean incremental,
      boolean watch) {
    this(
        wipeAllData,
        applySchema,
        wipeProjectCode,
        wipeProjectMemories,
        false,
        false,
        incremental,
        watch,
        EmbeddingSettings.disabled(),
        EmbeddingSettings.disabled());
  }

  public Settings(
      boolean wipeAllData,
      boolean applySchema,
      boolean wipeProjectCode,
      boolean wipeProjectMemories,
      boolean incremental,
      boolean watch,
      EmbeddingSettings codeEmbeddings,
      EmbeddingSettings memoryEmbeddings) {
    this(
        wipeAllData,
        applySchema,
        wipeProjectCode,
        wipeProjectMemories,
        false,
        false,
        incremental,
        watch,
        codeEmbeddings,
        memoryEmbeddings);
  }

  /** Ensures embedding settings are never null. */
  public Settings {
    codeEmbeddings = codeEmbeddings == null ? EmbeddingSettings.disabled() : codeEmbeddings;
    memoryEmbeddings = memoryEmbeddings == null ? EmbeddingSettings.disabled() : memoryEmbeddings;
  }

  public static Settings applySchemaOnly() {
    return new Settings(false, true, false, false, false, false);
  }

  public static Settings wipeAllAndApplySchema() {
    return new Settings(true, true, false, false, false, false);
  }

  public static Settings def() {
    return new Settings(false, false, false, false, false, false);
  }

  public static Settings wipeProjCodeOnly() {
    return new Settings(false, false, true, false, false, false);
  }
}
