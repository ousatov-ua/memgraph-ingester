package io.github.ousatov.tools.memgraph.vo;

/**
 * Settings
 *
 * @param applySchema if true, applies schema first
 * @param wipeProjectCode if true, deletes this project's code graph before ingesting
 * @param wipeProjectMemories if true, deletes this project's memory graph before ingesting
 * @param incremental if true, skips files whose lastModified matches the stored value; silently
 *     disabled when {@code wipeAllData} or {@code wipeProjectCode} is set, because wiping removes
 *     all stored timestamps making incremental comparison impossible
 * @param codeEmbeddings optional Memgraph-managed code chunk embedding refresh settings
 * @author Oleksii Usatov
 */
public record Settings(
    boolean wipeAllData,
    boolean applySchema,
    boolean wipeProjectCode,
    boolean wipeProjectMemories,
    boolean incremental,
    boolean watch,
    CodeEmbeddingSettings codeEmbeddings) {

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
        incremental,
        watch,
        CodeEmbeddingSettings.disabled());
  }

  /** Ensures code embedding settings are never null. */
  public Settings {
    codeEmbeddings = codeEmbeddings == null ? CodeEmbeddingSettings.disabled() : codeEmbeddings;
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
