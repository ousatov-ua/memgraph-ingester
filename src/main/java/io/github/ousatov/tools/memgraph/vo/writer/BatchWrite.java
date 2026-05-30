package io.github.ousatov.tools.memgraph.vo.writer;

import java.util.Map;

/**
 * Shared graph write payload contract for homogeneous Cypher batches.
 *
 * @author Oleksii Usatov
 */
public interface BatchWrite {

  Map<String, Object> params();
}
