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

  /**
   * Convenience constructor for runs without wipeCodeRag/wipeMemoryRag or embedding settings.
   *
   * @param incremental whether to skip files whose lastModified matches the stored value
   * @param watch whether to enable file-system watch mode after initial ingestion
   */
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

  /** Ensures embedding settings are never null. */
  public Settings {
    codeEmbeddings = codeEmbeddings == null ? EmbeddingSettings.disabled() : codeEmbeddings;
    memoryEmbeddings = memoryEmbeddings == null ? EmbeddingSettings.disabled() : memoryEmbeddings;
  }

  /** Default run with no schema changes and embeddings disabled. */
  public static Settings def() {
    return new Settings(
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        EmbeddingSettings.disabled(),
        EmbeddingSettings.disabled());
  }

  /** Run that applies schema without wiping. */
  public static Settings applySchemaOnly() {
    return new Settings(
        false,
        true,
        false,
        false,
        false,
        false,
        false,
        false,
        EmbeddingSettings.disabled(),
        EmbeddingSettings.disabled());
  }

  /** Run that wipes all data and then applies schema. */
  public static Settings wipeAllAndApplySchema() {
    return new Settings(
        true,
        true,
        false,
        false,
        false,
        false,
        false,
        false,
        EmbeddingSettings.disabled(),
        EmbeddingSettings.disabled());
  }

  /** Run that wipes only the project code graph. */
  public static Settings wipeProjCodeOnly() {
    return new Settings(
        false,
        false,
        true,
        false,
        false,
        false,
        false,
        false,
        EmbeddingSettings.disabled(),
        EmbeddingSettings.disabled());
  }
}
