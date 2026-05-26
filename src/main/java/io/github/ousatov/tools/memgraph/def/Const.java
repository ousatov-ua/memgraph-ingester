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

    public static final String CYPHER_UPSERT_ANNOTATED_WITH_BY_FQN_BATCH =
        action("upsert-annotated-with-by-fqn-batch.cypher");

    public static final String CYPHER_UPSERT_ANNOTATED_WITH_BY_SIG_BATCH =
        action("upsert-annotated-with-by-sig-batch.cypher");

    public static final String CYPHER_WIPE_PROJECT_CODE = action("wipe-project-code.cypher");
    public static final String CYPHER_WIPE_PROJECT_CODE_BATCH =
        action("wipe-project-code-batch.cypher");

    /** Batch-fetches Java {@code lastModified} values for files already present in the graph. */
    public static final String CYPHER_GET_JAVA_FILES_LAST_MODIFIED =
        action("Java/get-files-last-modified.cypher");

    /** Fetches Java project file paths under the active source root. */
    public static final String CYPHER_GET_JAVA_FILES_IN_SOURCE_ROOT =
        action("Java/get-files-in-source-root.cypher");

    /** Fetches source-root reconstruction metadata for one Java source file. */
    public static final String CYPHER_GET_JAVA_SOURCE_ROOT_HINT_FOR_FILE =
        action("Java/get-source-root-hint-for-file.cypher");

    /**
     * Batch-fetches JS/TS {@code lastModified} values for files whose module owner is already
     * present in the graph.
     */
    public static final String CYPHER_GET_JAVASCRIPT_FILES_LAST_MODIFIED =
        action("Js/get-files-last-modified.cypher");

    /** Fetches JS/TS project file paths under the active source root. */
    public static final String CYPHER_GET_JAVASCRIPT_FILES_IN_SOURCE_ROOT =
        action("Js/get-files-in-source-root.cypher");

    /** Fetches source-root reconstruction metadata for one JS/TS source file. */
    public static final String CYPHER_GET_JAVASCRIPT_SOURCE_ROOT_HINT_FOR_FILE =
        action("Js/get-source-root-hint-for-file.cypher");

    /** Batch-fetches Python {@code lastModified} values for fully ingested files. */
    public static final String CYPHER_GET_PYTHON_FILES_LAST_MODIFIED =
        action("Python/get-files-last-modified.cypher");

    /** Fetches Python project file paths under the active source root. */
    public static final String CYPHER_GET_PYTHON_FILES_IN_SOURCE_ROOT =
        action("Python/get-files-in-source-root.cypher");

    /** Fetches source-root reconstruction metadata for one Python source file. */
    public static final String CYPHER_GET_PYTHON_SOURCE_ROOT_HINT_FOR_FILE =
        action("Python/get-source-root-hint-for-file.cypher");

    /** Fetches retained source file paths outside the active source root. */
    public static final String CYPHER_GET_RETAINED_FILES_OUTSIDE_SOURCE_ROOT =
        action("get-retained-files-outside-source-root.cypher");

    /** Fetches project file paths under the active source root. */
    public static final String CYPHER_GET_FILES_IN_SOURCE_ROOT =
        action("get-files-in-source-root.cypher");

    /** Fetches retained files that define declarations shared with one source file. */
    public static final String CYPHER_GET_RETAINED_FILES_SHARING_DEFINITIONS_WITH_FILE =
        action("get-retained-files-sharing-definitions-with-file.cypher");

    public static final String CYPHER_WIPE_PROJECT_MEMORIES =
        action("wipe-project-memories.cypher");
    public static final String CYPHER_CLEAR_CODE_PACKAGE_CODE_REF_RESOLUTIONS =
        action("clear-code-package-code-ref-resolutions.cypher");
    public static final String CYPHER_RESOLVE_JAVA_CODE_REFS_CODE =
        action("Java/resolve-code-refs-code.cypher");
    public static final String CYPHER_RESOLVE_JAVA_CODE_REFS_PACKAGE =
        action("Java/resolve-code-refs-package.cypher");
    public static final String CYPHER_RESOLVE_JAVASCRIPT_CODE_REFS_CODE =
        action("Js/resolve-code-refs-code.cypher");
    public static final String CYPHER_RESOLVE_JAVASCRIPT_CODE_REFS_PACKAGE =
        action("Js/resolve-code-refs-package.cypher");
    public static final String CYPHER_RESOLVE_PYTHON_CODE_REFS_CODE =
        action("Python/resolve-code-refs-code.cypher");
    public static final String CYPHER_RESOLVE_PYTHON_CODE_REFS_PACKAGE =
        action("Python/resolve-code-refs-package.cypher");
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
    public static final String CYPHER_UPSERT_METHODS_BATCH = action("upsert-methods-batch.cypher");
    public static final String CYPHER_BACKFILL_METHOD_OWNER_METADATA =
        action("backfill-method-owner-metadata.cypher");

    /**
     * Callee is merged (not matched), creating a thin placeholder node if the callee file has not
     * been ingested yet. Placeholder nodes are later upgraded by the method upsert batch when the
     * callee file is processed; external/JDK callee nodes that are never upgraded are removed by
     * {@link #CYPHER_DELETE_PHANTOM_METHODS}.
     */
    public static final String CYPHER_UPSERT_CALLS_BATCH = action("upsert-calls-batch.cypher");

    /**
     * Fallback for unresolved same-class calls. Matches the callee by name within the owner type;
     * only creates the edge when exactly one method has that name (no overloading ambiguity).
     */
    public static final String CYPHER_UPSERT_CALL_BY_NAME = action("upsert-call-by-name.cypher");

    public static final String CYPHER_UPSERT_PENDING_CALLS_BY_NAME_BATCH =
        action("upsert-pending-calls-by-name-batch.cypher");

    /**
     * Deletes deferred owner/name calls for methods declared by a source file before re-ingestion.
     */
    public static final String CYPHER_DELETE_PENDING_CALLS_FOR_FILE =
        action("delete-pending-calls-for-file.cypher");

    /** Clears outgoing {@code CALLS} edges from methods declared by one source file. */
    public static final String CYPHER_DELETE_CALLS_FOR_FILE =
        action("delete-calls-for-file.cypher");

    /** Clears owner-level annotation edges for declarations in one source file. */
    public static final String CYPHER_DELETE_OWNER_ANNOTATIONS_FOR_FILE =
        action("delete-owner-annotations-for-file.cypher");

    /** Clears member-level annotation edges for declarations in one source file. */
    public static final String CYPHER_DELETE_MEMBER_ANNOTATIONS_FOR_FILE =
        action("delete-member-annotations-for-file.cypher");

    /** Clears inheritance and implementation edges for declarations in one source file. */
    public static final String CYPHER_DELETE_TYPE_RELATIONS_FOR_FILE =
        action("delete-type-relations-for-file.cypher");

    /**
     * Clears outgoing {@code CALLS} edges from methods declared by the owners currently present in
     * one source file.
     */
    public static final String CYPHER_DELETE_CURRENT_OWNER_CALLS_FOR_FILE =
        action("delete-current-owner-calls-for-file.cypher");

    /**
     * Clears owner-level annotation edges for declarations currently present in one source file.
     */
    public static final String CYPHER_DELETE_CURRENT_OWNER_ANNOTATIONS_FOR_FILE =
        action("delete-current-owner-annotations-for-file.cypher");

    /**
     * Clears member-level annotation edges for declarations currently present in one source file.
     */
    public static final String CYPHER_DELETE_CURRENT_MEMBER_ANNOTATIONS_FOR_FILE =
        action("delete-current-member-annotations-for-file.cypher");

    /**
     * Clears inheritance and implementation edges for declarations currently present in one source
     * file.
     */
    public static final String CYPHER_DELETE_CURRENT_TYPE_RELATIONS_FOR_FILE =
        action("delete-current-type-relations-for-file.cypher");

    /** Deletes member declarations absent from owners currently present in one source file. */
    public static final String CYPHER_DELETE_STALE_CURRENT_OWNER_MEMBERS_FOR_FILE =
        action("delete-stale-current-owner-members-for-file.cypher");

    /** Deletes method declarations that no longer exist in a source file. */
    public static final String CYPHER_DELETE_STALE_METHODS_FOR_FILE =
        action("delete-stale-methods-for-file.cypher");

    /** Deletes field declarations that no longer exist in a source file. */
    public static final String CYPHER_DELETE_STALE_FIELDS_FOR_FILE =
        action("delete-stale-fields-for-file.cypher");

    /** Deletes members of type declarations that no longer exist in a source file. */
    public static final String CYPHER_DELETE_STALE_OWNER_MEMBERS_FOR_FILE =
        action("delete-stale-owner-members-for-file.cypher");

    /** Deletes type declarations that no longer exist in a source file. */
    public static final String CYPHER_DELETE_STALE_OWNERS_FOR_FILE =
        action("delete-stale-owners-for-file.cypher");

    /** Deletes all member declarations for a removed source file. */
    public static final String CYPHER_DELETE_MEMBERS_FOR_FILE =
        action("delete-members-for-file.cypher");

    /** Deletes all type declarations for a removed source file. */
    public static final String CYPHER_DELETE_OWNERS_FOR_FILE =
        action("delete-owners-for-file.cypher");

    /** Deletes a removed source file node. */
    public static final String CYPHER_DELETE_FILE = action("delete-file.cypher");

    /** Deletes empty package nodes after source-file cleanup. */
    public static final String CYPHER_DELETE_EMPTY_PACKAGES =
        action("delete-empty-packages.cypher");

    /** Deletes deferred owner/name calls for files no longer present in the source tree. */
    public static final String CYPHER_DELETE_MISSING_FILE_PENDING_CALLS =
        action("delete-missing-file-pending-calls.cypher");

    /** Deletes member declarations for files no longer present in the source tree. */
    public static final String CYPHER_DELETE_MISSING_FILE_MEMBERS =
        action("delete-missing-file-members.cypher");

    /** Deletes type declarations for files no longer present in the source tree. */
    public static final String CYPHER_DELETE_MISSING_FILE_OWNERS =
        action("delete-missing-file-owners.cypher");

    /** Deletes file nodes no longer present in the source tree. */
    public static final String CYPHER_DELETE_MISSING_FILES = action("delete-missing-files.cypher");

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

    /** Deletes stale Python method and field declarations written under obsolete module FQNs. */
    public static final String CYPHER_DELETE_STALE_PYTHON_MEMBERS_FOR_FILE =
        action("Python/delete-stale-members-for-file.cypher");

    /** Deletes stale Python owners left behind by older module FQN schemes. */
    public static final String CYPHER_DELETE_STALE_PYTHON_OWNERS_FOR_FILE =
        action("Python/delete-stale-owners-for-file.cypher");

    /** Deletes empty Python package nodes left after stale owner cleanup. */
    public static final String CYPHER_DELETE_EMPTY_PYTHON_PACKAGES =
        action("Python/delete-empty-packages.cypher");

    /**
     * Resolves pending owner/name calls once all candidate owners and methods have been ingested.
     * Only creates the edge when exactly one method has that name (no overloading ambiguity).
     */
    public static final String CYPHER_RESOLVE_PENDING_CALLS =
        action("resolve-pending-calls.cypher");

    /**
     * Removes placeholder {@code :Method} nodes that were created by {@link
     * #CYPHER_UPSERT_CALLS_BATCH} but never fully ingested (i.e. external/JDK callees).
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

  public static class SystemParams {

    private SystemParams() {

      // Empty
    }

    public static final String DARWIN = "darwin";
    public static final String APPLE_DARWIN = "apple-darwin";
    public static final String LINUX = "linux";
    public static final String UNKNOWN_LINUX_GNU = "unknown-linux-gnu";
    public static final String AARCH_64 = "aarch64";
    public static final String ARM_64 = "arm64";
    public static final String AMD_64 = "amd64";
    public static final String X_86_64 = "x86_64";
    public static final String WINDOWS = "windows";
    public static final String PYTHON = "python";
  }

  /** Graph node label constants. */
  public static class Labels {

    public static final String CODE = "Code";
    public static final String INIT = "<init>";
    public static final String LANGUAGE = "Language";
    public static final String PACKAGE = "Package";
    public static final String PROJECT = "project";
    public static final String VOID = "void";

    private Labels() {

      // Empty
    }
  }

  /** Cypher parameter name constants. */
  public static class Params {

    public static final String CALLER = "caller";
    public static final String CALLER_SIGNATURE = "callerSignature";
    public static final String CALL = "call";
    public static final String CALL_BY_NAME = "callByName";
    public static final String CALLEE = "callee";
    public static final String CALLEE_NAME = "calleeName";
    public static final String CALLEE_OWNER_FQN = "calleeOwnerFqn";
    public static final String CALLEE_SIGNATURE = "calleeSignature";
    public static final String OWNER_FQN = "ownerFqn";
    public static final String OWNER_DISPLAY_NAME = "ownerDisplayName";
    public static final String FQN = "fqn";
    public static final String NAME = "name";
    public static final String ANNOT_FQN = "annotFqn";
    public static final String ANNOT_NAME = "annotName";
    public static final String PKG = "pkg";
    public static final String PACKAGE_NAME = "packageName";
    public static final String PATH = "path";
    public static final String PUBLIC = "public";
    public static final String IS_ABSTRACT = "isAbstract";
    public static final String VISIBILITY = "visibility";
    public static final String IS_ENUM = "isEnum";
    public static final String IS_RECORD = "isRecord";
    public static final String OWNER = "owner";
    public static final String OWNER_KEY = "ownerKey";
    public static final String OWNER_KIND = "ownerKind";
    public static final String CHILD = "child";
    public static final String CHILD_FQN = "childFqn";
    public static final String PARENT = "parent";
    public static final String PARENT_NAME = "parentName";
    public static final String PARENT_PKG = "parentPkg";
    public static final String IFACE = "iface";
    public static final String IFACE_NAME = "ifaceName";
    public static final String IFACE_PKG = "ifacePkg";
    public static final String SIG = "sig";
    public static final String IS_STATIC = "isStatic";
    public static final String RET = "ret";
    public static final String RECORD = "record";
    public static final String START = "start";
    public static final String END = "end";
    public static final String LAST_MODIFIED = "lastModified";
    public static final String IS_FINAL = "isFinal";
    public static final String IS_SYNTHETIC = "isSynthetic";
    public static final String TYPE = "type";
    public static final String TARGET_FQN = "targetFqn";
    public static final String DATA_TYPE = "dataType";
    public static final String LANGUAGE = "language";
    public static final String LANGUAGE_NAME = "languageName";
    public static final String KIND = "kind";
    public static final String KEY = "key";
    public static final String MEMBER_TYPE = "memberType";
    public static final String MODULE_PATH = "modulePath";
    public static final String MODULE_FQN = "moduleFqn";
    public static final String MODULE_NAME = "moduleName";
    public static final String MODULE_PREFIX = "modulePrefix";
    public static final String PATHS = "paths";
    public static final String RETAINED_PATHS = "retainedPaths";
    public static final String SOURCE_ROOT = "sourceRoot";
    public static final String SOURCE_ROOT_HINT = "sourceRootHint";
    public static final String SOURCE_ROOT_PREFIX = "sourceRootPrefix";
    public static final String CLASS_FQNS = "classFqns";
    public static final String INTERFACE_FQNS = "interfaceFqns";
    public static final String ANNOTATION_FQNS = "annotationFqns";
    public static final String METHOD_SIGNATURES = "methodSignatures";
    public static final String FIELD_FQNS = "fieldFqns";
    public static final String FRAMEWORK = "framework";
    public static final String END_LINE = "endLine";
    public static final String START_LINE = "startLine";
    public static final String ANNOTATION = "annotation";
    public static final String CLASS = "class";
    public static final String CLASS_EXTENDS = "classExtends";
    public static final String CONSTRUCTOR = "constructor";
    public static final String DECORATOR = "decorator";
    public static final String DEFAULT = "default";
    public static final String ENUM = "enum";
    public static final String ENUM_MEMBER = "enum-member";
    public static final String FIELD = "field";
    public static final String FUNCTION = "function";
    public static final String HAS_CONSTRUCTOR = "hasConstructor";
    public static final String IMPLEMENTS = "implements";
    public static final String INTERFACE = "interface";
    public static final String INTERFACE_EXTENDS = "interfaceExtends";
    public static final String MEMBER = "member";
    public static final String METHOD = "method";
    public static final String MODULE = "module";
    public static final String RELATION = "relation";

    private Params() {

      // Empty
    }
  }
}
