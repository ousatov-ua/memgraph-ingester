package io.github.ousatov.tools.memgraph.exe.smoke;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import io.github.ousatov.tools.memgraph.exe.analyze.CtagsAnalyzer;
import io.github.ousatov.tools.memgraph.exe.analyze.ManagedCtagsRuntime;
import io.github.ousatov.tools.memgraph.vo.analysis.CtagsAnalysis;
import io.github.ousatov.tools.memgraph.vo.analysis.RuntimeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smoke check for the managed Universal Ctags runtime.
 *
 * @author Oleksii Usatov
 */
public final class CtagsRuntimeSmokeCheck extends RuntimeSmokeCheck {

  private static final Logger log = LoggerFactory.getLogger(CtagsRuntimeSmokeCheck.class);

  private final Path cacheRoot;
  private final String ctagsVersion;
  private final RuntimeMode runtimeMode;

  public CtagsRuntimeSmokeCheck(Path cacheRoot, String ctagsVersion, RuntimeMode runtimeMode) {
    super(log);
    this.cacheRoot = cacheRoot;
    this.ctagsVersion = ctagsVersion;
    this.runtimeMode = runtimeMode;
  }

  @Override
  protected String displayName() {
    return "Universal Ctags";
  }

  @Override
  protected String tempDirPrefix() {
    return "memgraph-ingester-ctags-runtime-check-";
  }

  @Override
  protected Path cacheRoot() {
    return cacheRoot;
  }

  @Override
  protected void execute(Path tempDir) throws IOException {
    Path rubyFile = tempDir.resolve("service.rb");
    Files.writeString(
        rubyFile,
        """
        class Service
          def call
            1
          end
        end
        """);

    CtagsAnalyzer analyzer =
        new CtagsAnalyzer(tempDir, new ManagedCtagsRuntime(cacheRoot, ctagsVersion, runtimeMode));
    CtagsAnalysis analysis = analyzer.analyze(rubyFile);
    if (!"ruby".equals(analysis.language().graphName()) || analysis.types().isEmpty()) {
      throw new ProcessingException("Universal Ctags Ruby smoke check did not emit types");
    }
  }
}
