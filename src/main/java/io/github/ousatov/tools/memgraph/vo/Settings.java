package io.github.ousatov.tools.memgraph.vo;

/**
 * Settings
 *
 * @param applySchema if true, applies schema first
 * @param wipeProjectCode if true, deletes this project's code graph before ingesting
 * @param wipeProjectMemories if true, deletes this project's memory graph before ingesting
 * @param wipeCodeRag if true, deletes this project's derived CodeChunk rows before ingesting
 * @param wipeMemoryRag if true, deletes this project's derived MemoryChunk rows before ingesting
 * @param watch if true, enables file-system watch mode after initial ingestion
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
    boolean watch,
    EmbeddingSettings codeEmbeddings,
    EmbeddingSettings memoryEmbeddings) {

  /**
   * Convenience constructor for runs without wipeCodeRag/wipeMemoryRag or embedding settings.
   *
   * @param watch whether to enable file-system watch mode after initial ingestion
   */
  public Settings(
      boolean wipeAllData,
      boolean applySchema,
      boolean wipeProjectCode,
      boolean wipeProjectMemories,
      boolean watch) {
    this(
        wipeAllData,
        applySchema,
        wipeProjectCode,
        wipeProjectMemories,
        false,
        false,
        watch,
        EmbeddingSettings.disabled(),
        EmbeddingSettings.disabled());
  }

  /** Ensures embedding settings are never null. */
  public Settings {
    codeEmbeddings = codeEmbeddings == null ? EmbeddingSettings.disabled() : codeEmbeddings;
    memoryEmbeddings = memoryEmbeddings == null ? EmbeddingSettings.disabled() : memoryEmbeddings;
  }

  /**
   * Returns true when the run should skip unchanged files. Normal runs are incremental by default;
   * any wipe option disables the shortcut because data was intentionally removed before ingestion.
   */
  public boolean incremental() {
    return !(wipeAllData || wipeProjectCode || wipeProjectMemories || wipeCodeRag || wipeMemoryRag);
  }

  /** Default run with no schema changes and embeddings disabled. */
  public static Settings def() {
    return new Settings(false, false, false, false, false);
  }

  /** Run that applies schema without wiping. */
  public static Settings applySchemaOnly() {
    return new Settings(false, true, false, false, false);
  }

  /** Run that wipes all data and then applies schema. */
  public static Settings wipeAllAndApplySchema() {
    return new Settings(true, true, false, false, false);
  }

  /** Run that wipes only the project code graph. */
  public static Settings wipeProjCodeOnly() {
    return new Settings(false, false, true, false, false);
  }
}
