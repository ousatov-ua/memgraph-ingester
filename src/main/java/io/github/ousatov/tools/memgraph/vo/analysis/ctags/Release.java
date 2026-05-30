package io.github.ousatov.tools.memgraph.vo.analysis.ctags;

import java.util.List;

/**
 * Ctags release descriptor.
 *
 * @author Oleksii Usatov
 */
public record Release(String tag, List<ReleaseAsset> assets) {}
