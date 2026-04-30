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
 * @author Oleksii Usatov
 */
public record Settings(
    boolean wipeAllData,
    boolean applySchema,
    boolean wipeProjectCode,
    boolean wipeProjectMemories,
    boolean incremental) {}
