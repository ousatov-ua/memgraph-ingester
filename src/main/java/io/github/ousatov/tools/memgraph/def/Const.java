package io.github.ousatov.tools.memgraph.def;

import io.github.ousatov.tools.memgraph.exception.ProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Const
 *
 * @author Oleksii Usatov
 * @since 20.04.2026
 */
public class Const {

  private Const() {

    // Empty
  }

  public static class Cypher {

    private static final String ACTION_RESOURCE_BASE =
        "/io/github/ousatov/tools/memgraph/cypher/action/";

    /** Upserts an {@code @interface} declaration as an {@code :Annotation} node. */
    public static final String CYPHER_UPSERT_ANNOTATION = action("upsert-annotation.cypher");

    /**
     * Merges an {@code [:ANNOTATED_WITH]} edge from an element identified by {@code fqn} (Class,
     * Interface, Annotation, or Field) to an {@code :Annotation} node.
     */
    public static final String CYPHER_UPSERT_ANNOTATED_WITH_BY_FQN =
        action("upsert-annotated-with-by-fqn.cypher");

    /**
     * Merges an {@code [:ANNOTATED_WITH]} edge from a {@code :Method} identified by {@code
     * signature} to an {@code :Annotation} node.
     */
    public static final String CYPHER_UPSERT_ANNOTATED_WITH_BY_SIG =
        action("upsert-annotated-with-by-sig.cypher");

    public static final String CYPHER_WIPE_PROJECT_CODE = action("wipe-project-code.cypher");
    public static final String CYPHER_WIPE_PROJECT_MEMORIES =
        action("wipe-project-memories.cypher");
    public static final String CYPHER_UPSERT_PROJECT = action("upsert-project.cypher");
    public static final String CYPHER_UPSERT_FILE = action("upsert-file.cypher");
    public static final String CYPHER_UPSERT_PACKAGE = action("upsert-package.cypher");

    /**
     * Template for class/interface upsert — {@code %s} is replaced with {@code Class} or {@code
     * Interface} at call time.
     */
    public static final String CYPHER_UPSERT_TYPE_TEMPLATE = action("upsert-type-template.cypher");

    public static final String CYPHER_UPSERT_EXTENDS = action("upsert-extends.cypher");

    /** Used when an interface extends another interface — parent must be {@code :Interface}. */
    public static final String CYPHER_UPSERT_INTERFACE_EXTENDS =
        action("upsert-interface-extends.cypher");

    public static final String CYPHER_UPSERT_IMPLEMENTS = action("upsert-implements.cypher");
    public static final String CYPHER_UPSERT_FIELD = action("upsert-field.cypher");
    public static final String CYPHER_UPSERT_METHOD = action("upsert-method.cypher");

    /**
     * Callee is matched (not merged), so external library methods are never created as
     * project-scoped phantom nodes. Cross-file in-project calls missed on the first pass are filled
     * in by a subsequent wipe-less re-ingestion.
     */
    public static final String CYPHER_UPSERT_CALL = action("upsert-call.cypher");

    private static String action(String file) {
      String resource = ACTION_RESOURCE_BASE + file;
      try (InputStream in = Const.class.getResourceAsStream(resource)) {
        if (in == null) {
          throw new ProcessingException(resource + " is missing from jar");
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new ProcessingException(resource + " could not be loaded from jar", e);
      }
    }

    private Cypher() {

      // Empty
    }
  }

  public static class Labels {

    public static final String CALLER = "caller";
    public static final String CALLEE = "callee";
    public static final String FQN = "fqn";
    public static final String NAME = "name";
    public static final String CLASS = "Class";
    public static final String ANNOT_FQN = "annotFqn";
    public static final String PKG = "pkg";
    public static final String PATH = "path";
    public static final String IS_ABSTRACT = "isAbstract";
    public static final String VISIBILITY = "visibility";
    public static final String IS_ENUM = "isEnum";
    public static final String IS_RECORD = "isRecord";
    public static final String OWNER = "owner";
    public static final String CHILD = "child";
    public static final String PARENT = "parent";
    public static final String IFACE = "iface";
    public static final String SIG = "sig";
    public static final String IS_STATIC = "isStatic";
    public static final String RET = "ret";
    public static final String START = "start";
    public static final String END = "end";
    public static final String INIT = "<init>";
    public static final String VOID = "void";
    public static final String INTERFACE = "Interface";

    private Labels() {

      // Empty
    }
  }
}
