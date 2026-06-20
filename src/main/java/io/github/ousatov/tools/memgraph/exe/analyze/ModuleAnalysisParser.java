package io.github.ousatov.tools.memgraph.exe.analyze;

import io.github.ousatov.tools.memgraph.def.Const;
import io.github.ousatov.tools.memgraph.def.Const.Params;
import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Parses common NDJSON module records emitted by external analyzers. */
final class ModuleAnalysisParser {

  private ModuleAnalysisParser() {
    // Utility class.
  }

  static <A extends ModuleAnalysis> A parse(
      String stdout,
      Path file,
      String languageName,
      Function<String, Map<String, String>> objectParser,
      Factory<A> factory,
      Consumer<String> unknownRecord) {
    String moduleFqn = null;
    String moduleName = null;
    String packageName = null;
    String modulePath = null;
    int startLine = 1;
    int endLine = 1;
    List<io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl> types = new ArrayList<>();
    List<io.github.ousatov.tools.memgraph.vo.analysis.module.RelationDecl> relations =
        new ArrayList<>();
    List<io.github.ousatov.tools.memgraph.vo.analysis.module.MemberDecl> members =
        new ArrayList<>();
    List<io.github.ousatov.tools.memgraph.vo.analysis.module.AnnotationDecl> annotations =
        new ArrayList<>();
    List<io.github.ousatov.tools.memgraph.vo.analysis.module.CallDecl> calls = new ArrayList<>();

    for (String line : stdout.lines().filter(l -> !l.isBlank()).toList()) {
      Map<String, String> obj = objectParser.apply(line);
      switch (value(obj, Params.RECORD)) {
        case Params.MODULE -> {
          moduleFqn = value(obj, Params.MODULE_FQN);
          moduleName = value(obj, Params.MODULE_NAME);
          packageName = value(obj, Params.PACKAGE_NAME);
          modulePath = value(obj, Params.MODULE_PATH);
          startLine = intValue(obj, Params.START_LINE);
          endLine = intValue(obj, Params.END_LINE);
        }
        case Params.TYPE ->
            types.add(
                new io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl(
                    value(obj, Params.KIND),
                    value(obj, Params.FQN),
                    value(obj, Params.NAME),
                    value(obj, Params.FRAMEWORK),
                    booleanValue(obj, Params.HAS_CONSTRUCTOR),
                    booleanValue(obj, Params.IS_ABSTRACT),
                    intValue(obj, Params.START_LINE),
                    intValue(obj, Params.END_LINE)));
        case Params.RELATION ->
            relations.add(
                new io.github.ousatov.tools.memgraph.vo.analysis.module.RelationDecl(
                    value(obj, Params.KIND),
                    value(obj, Params.CHILD_FQN),
                    value(obj, Params.TARGET_FQN)));
        case Params.MEMBER ->
            members.add(
                new io.github.ousatov.tools.memgraph.vo.analysis.module.MemberDecl(
                    value(obj, Params.OWNER_FQN),
                    value(obj, Params.MEMBER_TYPE),
                    value(obj, Params.KIND),
                    value(obj, Params.KEY),
                    value(obj, Params.NAME),
                    value(obj, Params.DATA_TYPE),
                    booleanValue(obj, Params.IS_STATIC),
                    intValue(obj, Params.START_LINE),
                    intValue(obj, Params.END_LINE)));
        case Params.ANNOTATION ->
            annotations.add(
                new io.github.ousatov.tools.memgraph.vo.analysis.module.AnnotationDecl(
                    value(obj, Params.OWNER_KIND),
                    value(obj, Params.OWNER_KEY),
                    value(obj, Params.FQN),
                    value(obj, Params.NAME)));
        case Params.CALL, Params.CALL_BY_NAME ->
            calls.add(
                new io.github.ousatov.tools.memgraph.vo.analysis.module.CallDecl(
                    value(obj, Params.CALLER_SIGNATURE),
                    value(obj, Params.CALLEE_SIGNATURE),
                    value(obj, Params.CALLEE_OWNER_FQN),
                    value(obj, Params.CALLEE_NAME)));
        default -> unknownRecord.accept(line);
      }
    }
    if (moduleFqn == null) {
      throw new ProcessingException(
          languageName + " analyzer produced no module record for " + file);
    }
    return factory.create(
        moduleFqn,
        moduleName,
        packageName,
        modulePath,
        startLine,
        endLine,
        List.copyOf(types),
        List.copyOf(relations),
        List.copyOf(members),
        List.copyOf(annotations),
        List.copyOf(calls));
  }

  static <A extends ModuleAnalysis> List<A> parseBatch(
      String stdout,
      List<Path> files,
      String languageName,
      Function<String, Map<String, String>> objectParser,
      Factory<A> factory,
      Consumer<String> unknownRecord) {
    List<StringBuilder> groupedOutput = new ArrayList<>();
    int groupIndex = -1;
    for (String line : stdout.lines().filter(l -> !l.isBlank()).toList()) {
      Map<String, String> obj = objectParser.apply(line);
      if (Params.MODULE.equals(value(obj, Params.RECORD))) {
        groupIndex++;
        if (groupIndex >= files.size()) {
          throw new ProcessingException(languageName + " analyzer produced too many modules");
        }
        groupedOutput.add(new StringBuilder());
      }
      if (groupIndex < 0) {
        unknownRecord.accept(line);
        continue;
      }
      groupedOutput.get(groupIndex).append(line).append('\n');
    }
    if (groupedOutput.size() != files.size()) {
      throw new ProcessingException(
          languageName
              + " analyzer produced "
              + groupedOutput.size()
              + " module(s) for "
              + files.size()
              + " file(s)");
    }
    List<A> analyses = new ArrayList<>(files.size());
    for (int i = 0; i < files.size(); i++) {
      analyses.add(
          parse(
              groupedOutput.get(i).toString(),
              files.get(i),
              languageName,
              objectParser,
              factory,
              unknownRecord));
    }
    return List.copyOf(analyses);
  }

  private static String value(Map<String, String> obj, String key) {
    return obj.getOrDefault(key, Const.Symbols.EMPTY);
  }

  private static int intValue(Map<String, String> obj, String key) {
    String value = value(obj, key);
    return value.isBlank() ? 0 : Integer.parseInt(value);
  }

  private static boolean booleanValue(Map<String, String> obj, String key) {
    return Boolean.parseBoolean(value(obj, key));
  }

  @FunctionalInterface
  @SuppressWarnings(Const.Warnings.TOO_MANY_PARAMETERS)
  interface Factory<A extends ModuleAnalysis> {

    A create(
        String moduleFqn,
        String moduleName,
        String packageName,
        String modulePath,
        int startLine,
        int endLine,
        List<io.github.ousatov.tools.memgraph.vo.analysis.module.TypeDecl> types,
        List<io.github.ousatov.tools.memgraph.vo.analysis.module.RelationDecl> relations,
        List<io.github.ousatov.tools.memgraph.vo.analysis.module.MemberDecl> members,
        List<io.github.ousatov.tools.memgraph.vo.analysis.module.AnnotationDecl> annotations,
        List<io.github.ousatov.tools.memgraph.vo.analysis.module.CallDecl> calls);
  }
}
