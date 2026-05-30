package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.exe.adapter.SourceLanguage;
import io.github.ousatov.tools.memgraph.vo.analysis.ctags.MemberDecl;
import io.github.ousatov.tools.memgraph.vo.analysis.ctags.TypeDecl;
import java.util.List;

/**
 * Source structure emitted by Universal Ctags before graph writes.
 *
 * @author Oleksii Usatov
 */
public record CtagsAnalysis(
    SourceLanguage language,
    String moduleFqn,
    String moduleName,
    String packageName,
    String modulePath,
    int startLine,
    int endLine,
    List<TypeDecl> types,
    List<MemberDecl> members) {}
