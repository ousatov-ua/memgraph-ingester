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
public final class Const {

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
    public static final String CYPHER_WIPE_PROJECT_CODE_BATCH =
        action("wipe-project-code-batch.cypher");

    /**
     * Batch-fetches {@code lastModified} for a list of file paths in one query. Returns one row per
     * file found in the graph; files absent from the graph are not included.
     */
    public static final String CYPHER_GET_FILES_LAST_MODIFIED =
        action("get-files-last-modified.cypher");

    public static final String CYPHER_WIPE_PROJECT_MEMORIES =
        action("wipe-project-memories.cypher");
    public static final String CYPHER_RESOLVE_CODE_REFS_CODE =
        action("resolve-code-refs-code.cypher");
    public static final String CYPHER_RESOLVE_CODE_REFS_PACKAGE =
        action("resolve-code-refs-package.cypher");
    public static final String CYPHER_RESOLVE_CODE_REFS_FILE =
        action("resolve-code-refs-file.cypher");
    public static final String CYPHER_RESOLVE_CODE_REFS_CLASS =
        action("resolve-code-refs-class.cypher");
    public static final String CYPHER_RESOLVE_CODE_REFS_INTERFACE =
        action("resolve-code-refs-interface.cypher");
    public static final String CYPHER_RESOLVE_CODE_REFS_ANNOTATION =
        action("resolve-code-refs-annotation.cypher");
    public static final String CYPHER_RESOLVE_CODE_REFS_METHOD =
        action("resolve-code-refs-method.cypher");
    public static final String CYPHER_RESOLVE_CODE_REFS_FIELD =
        action("resolve-code-refs-field.cypher");
    public static final String CYPHER_UPSERT_PROJECT = action("upsert-project.cypher");
    public static final String CYPHER_UPSERT_FILE = action("upsert-file.cypher");
    public static final String CYPHER_UPSERT_PACKAGE = action("upsert-package.cypher");

    /** Upserts a class (including enums and records) as a {@code :Class} node. */
    public static final String CYPHER_UPSERT_CLASS = action("upsert-class.cypher");

    /** Upserts an interface as an {@code :Interface} node. */
    public static final String CYPHER_UPSERT_INTERFACE = action("upsert-interface.cypher");

    public static final String CYPHER_UPSERT_EXTENDS_CLASS = action("upsert-class-extends.cypher");

    /** Used when an interface extends another interface — parent must be {@code :Interface}. */
    public static final String CYPHER_UPSERT_INTERFACE_EXTENDS =
        action("upsert-interface-extends.cypher");

    public static final String CYPHER_UPSERT_IMPLEMENTS = action("upsert-implements.cypher");
    public static final String CYPHER_UPSERT_FIELD = action("upsert-field.cypher");
    public static final String CYPHER_UPSERT_METHOD = action("upsert-method.cypher");
    public static final String CYPHER_BACKFILL_METHOD_OWNER_METADATA =
        action("backfill-method-owner-metadata.cypher");

    /**
     * Callee is merged (not matched), creating a thin placeholder node if the callee file has not
     * been ingested yet. Placeholder nodes are later upgraded by {@code upsert-method.cypher} when
     * the callee file is processed; external/JDK callee nodes that are never upgraded are removed
     * by {@link #CYPHER_DELETE_PHANTOM_METHODS}.
     */
    public static final String CYPHER_UPSERT_CALL = action("upsert-call.cypher");

    /**
     * Fallback for unresolved same-class calls. Matches the callee by name within the owner type;
     * only creates the edge when exactly one method has that name (no overloading ambiguity).
     */
    public static final String CYPHER_UPSERT_CALL_BY_NAME = action("upsert-call-by-name.cypher");

    /** Stores unresolved owner/name calls until a post-ingestion resolution pass can match them. */
    public static final String CYPHER_UPSERT_PENDING_CALL_BY_NAME =
        action("upsert-pending-call-by-name.cypher");

    /**
     * Deletes deferred owner/name calls for methods declared by a source file before re-ingestion.
     */
    public static final String CYPHER_DELETE_PENDING_CALLS_FOR_FILE =
        action("delete-pending-calls-for-file.cypher");

    /**
     * Deletes stale JavaScript/TypeScript method and field declarations whose owners were
     * previously written for the same file under an obsolete module FQN.
     */
    public static final String CYPHER_DELETE_STALE_JAVASCRIPT_MEMBERS_FOR_FILE =
        action("Js/delete-stale-members-for-file.cypher");

    /** Deletes stale JavaScript/TypeScript owners left behind by older module FQN schemes. */
    public static final String CYPHER_DELETE_STALE_JAVASCRIPT_OWNERS_FOR_FILE =
        action("Js/delete-stale-owners-for-file.cypher");

    /** Deletes empty JavaScript/TypeScript package nodes left after stale owner cleanup. */
    public static final String CYPHER_DELETE_EMPTY_JAVASCRIPT_PACKAGES =
        action("Js/delete-empty-packages.cypher");

    /**
     * Resolves pending owner/name calls once all candidate owners and methods have been ingested.
     * Only creates the edge when exactly one method has that name (no overloading ambiguity).
     */
    public static final String CYPHER_RESOLVE_PENDING_CALLS =
        action("resolve-pending-calls.cypher");

    /**
     * Removes placeholder {@code :Method} nodes that were created by {@link #CYPHER_UPSERT_CALL}
     * but never fully ingested (i.e. external/JDK callees).
     */
    public static final String CYPHER_DELETE_PHANTOM_METHODS =
        action("delete-phantom-methods.cypher");

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

  /** Graph node label constants. */
  public static class Labels {

    public static final String INIT = "<init>";
    public static final String VOID = "void";
    public static final String PROJECT = "project";

    private Labels() {

      // Empty
    }
  }

  /** Cypher parameter name constants. */
  public static class Params {

    public static final String CALLER = "caller";
    public static final String CALLEE = "callee";
    public static final String CALLEE_NAME = "calleeName";
    public static final String OWNER_FQN = "ownerFqn";
    public static final String OWNER_DISPLAY_NAME = "ownerDisplayName";
    public static final String FQN = "fqn";
    public static final String NAME = "name";
    public static final String ANNOT_FQN = "annotFqn";
    public static final String ANNOT_NAME = "annotName";
    public static final String PKG = "pkg";
    public static final String PATH = "path";
    public static final String IS_ABSTRACT = "isAbstract";
    public static final String VISIBILITY = "visibility";
    public static final String IS_ENUM = "isEnum";
    public static final String IS_RECORD = "isRecord";
    public static final String OWNER = "owner";
    public static final String CHILD = "child";
    public static final String PARENT = "parent";
    public static final String PARENT_NAME = "parentName";
    public static final String PARENT_PKG = "parentPkg";
    public static final String IFACE = "iface";
    public static final String IFACE_NAME = "ifaceName";
    public static final String IFACE_PKG = "ifacePkg";
    public static final String SIG = "sig";
    public static final String IS_STATIC = "isStatic";
    public static final String RET = "ret";
    public static final String START = "start";
    public static final String END = "end";
    public static final String LAST_MODIFIED = "lastModified";
    public static final String IS_FINAL = "isFinal";
    public static final String IS_SYNTHETIC = "isSynthetic";
    public static final String TYPE = "type";
    public static final String LANGUAGE = "language";
    public static final String LANGUAGE_NAME = "languageName";
    public static final String KIND = "kind";
    public static final String MODULE_PATH = "modulePath";
    public static final String MODULE_PREFIX = "modulePrefix";
    public static final String FRAMEWORK = "framework";
    public static final String END_LINE = "endLine";
    public static final String START_LINE = "startLine";
    public static final String ANNOTATION = "annotation";

    private Params() {

      // Empty
    }
  }
}
