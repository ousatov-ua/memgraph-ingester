package io.github.ousatov.tools.memgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParseService}. These tests use only the filesystem — no Memgraph required.
 *
 * @author Oleksii Usatov
 */
class ParseServiceTest {

  private Path tempDir;
  private ParseService parseService;

  @BeforeEach
  void setup() throws IOException {
    tempDir = Files.createTempDirectory("parse-test-");
    parseService = new ParseService(tempDir);
  }

  @AfterEach
  void cleanup() throws IOException {
    try (Stream<Path> walk = Files.walk(tempDir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                var _ = p.toFile().delete();
              });
    }
  }

  @Test
  void parsesValidClass() throws IOException {
    Path file = tempDir.resolve("Hello.java");
    Files.writeString(file, "public class Hello {}");

    Optional<CompilationUnit> result = parseService.parse(file);

    assertTrue(result.isPresent());
    ClassOrInterfaceDeclaration decl =
        result.get().findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    assertEquals("Hello", decl.getNameAsString());
  }

  @Test
  void parsesValidInterface() throws IOException {
    Path file = tempDir.resolve("Greetable.java");
    Files.writeString(file, "public interface Greetable { String greet(); }");

    Optional<CompilationUnit> result = parseService.parse(file);

    assertTrue(result.isPresent());
    ClassOrInterfaceDeclaration decl =
        result.get().findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    assertTrue(decl.isInterface());
    assertEquals("Greetable", decl.getNameAsString());
  }

  @Test
  void returnsEmptyOnSyntaxError() throws IOException {
    Path file = tempDir.resolve("Bad.java");
    Files.writeString(file, "this is {{ not valid java");

    Optional<CompilationUnit> result = parseService.parse(file);

    assertTrue(result.isEmpty());
  }

  @Test
  void returnsEmptyForMissingFile() {
    Optional<CompilationUnit> result = parseService.parse(tempDir.resolve("Missing.java"));

    assertTrue(result.isEmpty());
  }

  @Test
  void isThreadSafe() throws Exception {
    Path file = tempDir.resolve("Shared.java");
    Files.writeString(
        file,
        """
        public class Shared {
          private int value;
          public int getValue() { return value; }
        }
        """);

    int threadCount = 8;
    Callable<Optional<CompilationUnit>> task = () -> parseService.parse(file);
    List<Callable<Optional<CompilationUnit>>> tasks = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      tasks.add(task);
    }

    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    List<Future<Optional<CompilationUnit>>> futures = pool.invokeAll(tasks);
    pool.shutdown();

    for (Future<Optional<CompilationUnit>> f : futures) {
      assertTrue(f.get().isPresent(), "Each thread must successfully parse the file");
    }
  }
}
