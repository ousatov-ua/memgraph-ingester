package io.github.ousatov.tools.memgraph.vo.analysis.ctags;

import java.util.Optional;

/**
 * Ctags release asset descriptor.
 *
 * @author Oleksii Usatov
 */
public record ReleaseAsset(String tag, String name, Optional<String> digest, String url) {}
