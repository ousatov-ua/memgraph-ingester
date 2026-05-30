package io.github.ousatov.tools.memgraph.vo.rag;

import io.github.ousatov.tools.memgraph.def.Const;
import java.util.List;

/**
 * Canonical Memory node data needed to build one derived chunk.
 *
 * @author Oleksii Usatov
 */
@SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
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
