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

    public static final String CYPHER_UPSERT_ANNOTATED_WITH_BY_FQN_BATCH =
        action("upsert-annotated-with-by-fqn-batch.cypher");

    public static final String CYPHER_UPSERT_ANNOTATED_WITH_BY_SIG_BATCH =
        action("upsert-annotated-with-by-sig-batch.cypher");

    public static final String CYPHER_WIPE_PROJECT_CODE = action("wipe-project-code.cypher");
    public static final String CYPHER_WIPE_PROJECT_CODE_BATCH =
        action("wipe-project-code-batch.cypher");
    public static final String CYPHER_WIPE_CODE_RAG_BATCH =
        action("embedding/wipe-code-rag-batch.cypher");
    public static final String CYPHER_DELETE_CODE_CHUNKS_FOR_FILE =
        action("delete-code-chunks-for-file.cypher");
    public static final String CYPHER_DELETE_CODE_CHUNKS_FOR_FILES =
        action("delete-code-chunks-for-files.cypher");
    public static final String CYPHER_DELETE_CODE_CHUNKS_FOR_FILE_EXCEPT =
        action("delete-code-chunks-for-file-except.cypher");
    public static final String CYPHER_GET_FILE_PATHS_MISSING_CODE_CHUNKS =
        action("get-file-paths-missing-code-chunks.cypher");
    public static final String CYPHER_UPSERT_CODE_CHUNKS_BATCH =
        action("upsert-code-chunks-batch.cypher");
    public static final String CYPHER_LINK_FILE_CODE_CHUNKS_BATCH =
        action("link-file-code-chunks-batch.cypher");
    public static final String CYPHER_LINK_CLASS_CODE_CHUNKS_BATCH =
        action("link-class-code-chunks-batch.cypher");
    public static final String CYPHER_LINK_INTERFACE_CODE_CHUNKS_BATCH =
        action("link-interface-code-chunks-batch.cypher");
    public static final String CYPHER_LINK_ANNOTATION_CODE_CHUNKS_BATCH =
        action("link-annotation-code-chunks-batch.cypher");
    public static final String CYPHER_LINK_METHOD_CODE_CHUNKS_BATCH =
        action("link-method-code-chunks-batch.cypher");
    public static final String CYPHER_LINK_FIELD_CODE_CHUNKS_BATCH =
        action("link-field-code-chunks-batch.cypher");
    public static final String CYPHER_CODE_EMBEDDING_MODEL_INFO =
        action("embedding/code-embedding-model-info.cypher");
    public static final String CYPHER_CREATE_CODE_CHUNK_VECTOR_INDEX =
        action("embedding/create-code-chunk-vector-index.cypher");
    public static final String CYPHER_SHOW_VECTOR_INDEX_INFO =
        action("embedding/show-vector-index-info.cypher");
    public static final String CYPHER_COUNT_CODE_CHUNKS =
        action("embedding/count-code-chunks.cypher");
    public static final String CYPHER_COUNT_DIRTY_CODE_CHUNK_EMBEDDINGS =
        action("embedding/count-dirty-code-chunk-embeddings.cypher");
    public static final String CYPHER_MARK_STALE_CODE_CHUNK_EMBEDDINGS =
        action("embedding/mark-stale-code-chunk-embeddings.cypher");
    public static final String CYPHER_REFRESH_CODE_CHUNK_EMBEDDING_BATCH =
        action("embedding/refresh-code-chunk-embedding-batch.cypher");
    public static final String CYPHER_UPDATE_CODE_CHUNK_EMBEDDING_METADATA =
        action("embedding/update-code-chunk-embedding-metadata.cypher");
    public static final String CYPHER_GET_CODE_CHUNK_EMBEDDING_FAILURE_DETAIL =
        action("embedding/get-code-chunk-embedding-failure-detail.cypher");
    public static final String CYPHER_CREATE_MEMORY_CHUNK_VECTOR_INDEX =
        action("embedding/create-memory-chunk-vector-index.cypher");
    public static final String CYPHER_COUNT_MEMORY_CHUNKS =
        action("embedding/count-memory-chunks.cypher");
    public static final String CYPHER_LIST_MEMORY_CHUNK_SOURCES =
        action("embedding/list-memory-chunk-sources.cypher");
    public static final String CYPHER_DELETE_STALE_MEMORY_CHUNKS =
        action("embedding/delete-stale-memory-chunks.cypher");
    public static final String CYPHER_WIPE_MEMORY_RAG_BATCH =
        action("embedding/wipe-memory-rag-batch.cypher");
    public static final String CYPHER_UPSERT_MEMORY_CHUNKS_BATCH =
        action("embedding/upsert-memory-chunks-batch.cypher");
    public static final String CYPHER_MARK_STALE_MEMORY_CHUNK_EMBEDDINGS =
        action("embedding/mark-stale-memory-chunk-embeddings.cypher");
    public static final String CYPHER_REFRESH_MEMORY_CHUNK_EMBEDDING_BATCH =
        action("embedding/refresh-memory-chunk-embedding-batch.cypher");
    public static final String CYPHER_UPDATE_MEMORY_CHUNK_EMBEDDING_METADATA =
        action("embedding/update-memory-chunk-embedding-metadata.cypher");
    public static final String CYPHER_GET_MEMORY_CHUNK_EMBEDDING_FAILURE_DETAIL =
        action("embedding/get-memory-chunk-embedding-failure-detail.cypher");

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

    /** Fetches graph languages represented under the active source root. */
    public static final String CYPHER_GET_LANGUAGES_IN_SOURCE_ROOT =
        action("get-languages-in-source-root.cypher");

    /** Fetches project file paths under the active source root. */
    public static final String CYPHER_GET_FILES_IN_SOURCE_ROOT =
        action("get-files-in-source-root.cypher");

    /** Generic ctags last-modified lookup for dynamically detected languages. */
    public static final String CYPHER_GET_CTAGS_FILES_LAST_MODIFIED =
        action("Ctags/get-files-last-modified.cypher");

    /** Generic ctags file lookup under the active source root. */
    public static final String CYPHER_GET_CTAGS_FILES_IN_SOURCE_ROOT =
        action("Ctags/get-files-in-source-root.cypher");

    /** Generic ctags source-root reconstruction metadata for one source file. */
    public static final String CYPHER_GET_CTAGS_SOURCE_ROOT_HINT_FOR_FILE =
        action("Ctags/get-source-root-hint-for-file.cypher");

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
    public static final String CYPHER_RESOLVE_DYNAMIC_CODE_REFS_CODE =
        action("resolve-dynamic-code-refs-code.cypher");
    public static final String CYPHER_RESOLVE_DYNAMIC_CODE_REFS_PACKAGE =
        action("resolve-dynamic-code-refs-package.cypher");
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

    public static final String CYPHER_UPSERT_CLASSES_BATCH = action("upsert-classes-batch.cypher");

    public static final String CYPHER_UPSERT_INTERFACES_BATCH =
        action("upsert-interfaces-batch.cypher");

    public static final String CYPHER_UPSERT_ANNOTATIONS_BATCH =
        action("upsert-annotations-batch.cypher");

    public static final String CYPHER_UPSERT_EXTENDS_CLASS_BATCH =
        action("upsert-class-extends-batch.cypher");

    public static final String CYPHER_UPSERT_INTERFACE_EXTENDS_BATCH =
        action("upsert-interface-extends-batch.cypher");

    public static final String CYPHER_UPSERT_IMPLEMENTS_BATCH =
        action("upsert-implements-batch.cypher");
    public static final String CYPHER_UPSERT_FIELDS_BATCH = action("upsert-fields-batch.cypher");
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

    public static final String CYPHER_UPSERT_CALLS_BY_NAME_BATCH =
        action("upsert-calls-by-name-batch.cypher");

    public static final String CYPHER_UPSERT_PENDING_CALLS_BY_NAME_BATCH =
        action("upsert-pending-calls-by-name-batch.cypher");

    /**
     * Deletes deferred owner/name calls for methods declared by a source file before re-ingestion.
     */
    public static final String CYPHER_DELETE_PENDING_CALLS_FOR_FILE =
        action("delete-pending-calls-for-file.cypher");

    /** Clears file-local stale definitions and relationships in one batched Cypher statement. */
    public static final String CYPHER_DELETE_STALE_DEFINITIONS_FOR_FILE =
        action("delete-stale-definitions-for-file.cypher");

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

    public static final String CYPHER_RESOLVE_PENDING_CALLS_SCOPED =
        action("resolve-pending-calls-scoped.cypher");

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

  /** Reusable literal values that carry formatting or syntax meaning. */
  public static class Symbols {

    public static final String EMPTY = "";
    public static final String SPACE = " ";
    public static final String TWO_SPACES = "  ";
    public static final String DOT = ".";
    public static final String HASH = "#";
    public static final String LEFT_PAREN = "(";
    public static final String RIGHT_PAREN = ")";
    public static final String PARENS = "()";
    public static final String COMMA_SPACE = ", ";
    public static final String COLON_SPACE = ": ";
    public static final String NEW_LINE = "\n";
    public static final String DASH = "-";
    public static final String PLUS = "+";
    public static final String SLASH = "/";
    public static final String DOUBLE_COLON = "::";
    public static final String DOT_REGEX = "\\.";
    public static final String SINGLE_QUOTE = "'";
    public static final String DOUBLE_QUOTE = "\"";
    public static final String CRLF = "\r\n";
    public static final String DOLLAR = "$";
    public static final String SPACE_LEFT_PAREN = " (";
    public static final String RIGHT_PAREN_COLON_SPACE = "): ";
    public static final String COMMA_GOT = ", got ";
    public static final String COMMA_PREVIEW = ", preview=";
    public static final String COMMA_SOURCE = ", source=";
    public static final String DOUBLE_SLASH = "//";
    public static final String PIPE_PREFIX = "| ";
    public static final String PIPE = "|";
    public static final String PIPE_SEPARATOR = " | ";
    public static final String TABLE_ROW_SUFFIX = " |";
    public static final String DOUBLE_DASH = "--";

    private Symbols() {

      // Empty
    }
  }

  /** Sonar and compiler warning identifiers. */
  public static class Warnings {

    public static final String UNUSED = "unused";
    public static final String TOO_MANY_PARAMETERS = "java:S107";
    public static final String EMPTY_METHOD = "java:S1186";
    public static final String STANDARD_OUTPUT = "java:S106";
    public static final String IGNORED_RETURN_VALUE = "java:S899";
    public static final String BROAD_EXCEPTION = "java:S1181";
    public static final String LOOP_CONTROL = "java:S135";
    public static final String COGNITIVE_COMPLEXITY = "java:S3776";

    private Warnings() {

      // Empty
    }
  }

  /** Shared file and directory names. */
  public static class Files {

    public static final String NODE_MODULES = "node_modules";
    public static final String PYCACHE = "__pycache__";
    public static final String BIN = "bin";
    public static final String BUILD = "build";
    public static final String DIST = "dist";
    public static final String VENV = "venv";
    public static final String VIRTUAL_ENV = ".venv";
    public static final String TOX = ".tox";
    public static final String NOX = ".nox";
    public static final String INSTALL_COMPLETE = ".install-complete";
    public static final String INSTALL_LOCK = ".install.lock";
    public static final String ZIP = ".zip";
    public static final String TAR_GZ = ".tar.gz";
    public static final String TAR_XZ = ".tar.xz";
    public static final String JAVA_EXTENSION = ".java";
    public static final String PYTHON_EXTENSION = ".py";
    public static final String PYTHON_STUB_EXTENSION = ".pyi";
    public static final String JAVASCRIPT_EXTENSION = ".js";
    public static final String JAVASCRIPT_MODULE_EXTENSION = ".mjs";
    public static final String TYPESCRIPT_EXTENSION = ".ts";
    public static final String TYPESCRIPT_MODULE_EXTENSION = ".mts";
    public static final String TYPESCRIPT_COMMONJS_EXTENSION = ".cts";
    public static final String JSX_EXTENSION = ".jsx";
    public static final String TSX_EXTENSION = ".tsx";
    public static final String COMMONJS_EXTENSION = ".cjs";
    public static final String NODE_EXE = "node.exe";
    public static final String PYTHON_EXE = "python.exe";
    public static final String PYTHON3 = "python3";
    public static final String SITE_PACKAGES = "site-packages";
    public static final String TARGET = "target";
    public static final String OUT = "out";

    private Files() {

      // Empty
    }
  }

  /** Command line option names and aliases. */
  public static class Cli {

    public static final String USER_SHORT = "-u";
    public static final String USER = "--user";
    public static final String PASS_SHORT = "-p";
    public static final String PASS = "--pass";
    public static final String THREADS_SHORT = "-t";
    public static final String THREADS = "--threads";
    public static final String CLASSPATH = "--classpath";
    public static final String INSTRUCTIONS_AGENT = "--instructions-agent";
    public static final String NO_MEMGRAPH_INGESTER_MCP = "--no-memgraph-ingester-mcp";
    public static final String JS_RUNTIME_MODE = "--js-runtime-mode";
    public static final String JS_RUNTIME_CACHE = "--js-runtime-cache";
    public static final String JS_NODE_VERSION = "--js-node-version";
    public static final String JS_TYPESCRIPT_VERSION = "--js-typescript-version";
    public static final String PYTHON_RUNTIME_MODE = "--python-runtime-mode";
    public static final String PYTHON_RUNTIME_CACHE = "--python-runtime-cache";
    public static final String PYTHON_VERSION = "--python-version";
    public static final String PYTHON_BUILD = "--python-build";
    public static final String ROOT = "--root";
    public static final String FILE = "--file";
    public static final String CTAGS_OPTIONS_NONE = "--options=NONE";

    private Cli() {

      // Empty
    }
  }

  public static class Rag {

    public static final String DIMENSION = "dimension";
    public static final String METRIC = "metric";
    public static final String SCALAR_KIND = "scalar_kind";
    public static final String CAPACITY = "capacity";
    public static final String BATCH_SIZE = "batch_size";
    public static final String CHUNK_SIZE = "chunk_size";

    private Rag() {

      // Empty
    }
  }

  public static class SystemParams {

    private SystemParams() {

      // Empty
    }

    public static final String MACOS = "macos";
    public static final String X86 = "x86";
    public static final String I_386 = "i386";
    public static final String I_686 = "i686";
    public static final String X_64 = "x64";
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
    public static final String PYTHON_DISPLAY = "Python";
    public static final String JAVA = "java";
    public static final String CTAGS = "ctags";
    public static final String NODE = "node";
    public static final String MANAGED = "managed";
    public static final String VERSION_PREFIX = "v";
    public static final String CODEX = "codex";
    public static final String MEMGRAPH_INGESTER = "memgraph-ingester";
    public static final String SHA_256 = "SHA-256";
    public static final String CPP = "cpp";
    public static final String CSHARP = "csharp";
    public static final String FSHARP = "fsharp";
    public static final String NULL = "null";
    public static final String TYPESCRIPT = "typescript";
    public static final String WINDOWS_PREFIX = "win";
    public static final String SHA_256_PREFIX = "sha256:";
    public static final String OS_NAME = "os.name";
  }

  /** Graph node label constants. */
  public static class Labels {

    public static final String ANNOTATION = "Annotation";
    public static final String CLASS = "Class";
    public static final String CODE = "Code";
    public static final String CODE_CHUNK = "CodeChunk";
    public static final String FIELD = "Field";
    public static final String FILE = "File";
    public static final String INIT = "<init>";
    public static final String INTERFACE = "Interface";
    public static final String LANGUAGE = "Language";
    public static final String MEMORY_CHUNK = "MemoryChunk";
    public static final String METHOD = "Method";
    public static final String PACKAGE = "Package";
    public static final String PENDING_CALL = "PendingCall";
    public static final String PROJECT = "project";
    public static final String VOID = "void";

    private Labels() {

      // Empty
    }
  }

  /** Cypher parameter name constants. */
  public static class Params {

    public static final String CALLER = "caller";
    public static final String OUT = "out";
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
    public static final String ID = "id";
    public static final String ID_EQUALS = "id=";
    public static final String ZERO = "0";
    public static final String ONE = "1";
    public static final String MODEL_NAME = "modelName";
    public static final String EMBEDDING_MODEL = "embeddingModel";
    public static final String EMBEDDING_DIMENSIONS = "embeddingDimensions";
    public static final String COUNT = "count";
    public static final String CONFIG = "config";
    public static final String LABEL = "label";
    public static final String PROPERTY = "property";
    public static final String SIGNATURE = "signature";
    public static final String STDOUT = "stdout";
    public static final String STDERR = "stderr";
    public static final String CACHE_ROOT = "cacheRoot";
    public static final String RUNTIME_MODE = "runtimeMode";
    public static final String BATCH_SIZE = "batchSize";
    public static final String DELETED = "deleted";
    public static final String CREATED_AT = "createdAt";
    public static final String EMBEDDING_DIRTY = "embeddingDirty";
    public static final String EMBEDDING_PROPERTY = "embedding_property";
    public static final String EXCLUDED_PROPERTIES = "excluded_properties";
    public static final String LIMIT = "limit";
    public static final String PREVIEW = "preview";
    public static final String PROPERTIES = "properties";
    public static final String RECORD_COMPONENT = "record-component";
    public static final String RETURN_EMBEDDINGS = "return_embeddings";
    public static final String ROWS = "rows";
    public static final String TAG = "tag";
    public static final String TARGET = "target";
    public static final String TARGET_NAME = "targetName";
    public static final String TARGET_PKG = "targetPkg";
    public static final String TRUE = "true";
    public static final String UNIQUE = "unique";
    public static final String UPDATED_AT = "updatedAt";
    public static final String VALUE = "value";
    public static final String SHOW_CONSTRAINT_INFO = "SHOW CONSTRAINT INFO";
    public static final String CONSTRAINT_TYPE = "constraint type";
    public static final String CODE_EMBEDDING_INDEX_NAME = "code embedding index name";
    public static final String MEMORY_EMBEDDING_INDEX_NAME = "memory embedding index name";
    public static final String INDEX_NAME_PLACEHOLDER = "__INDEX_NAME__";
    public static final String EMBEDDING_PROPERTY_PLACEHOLDER = "__EMBEDDING_PROPERTY__";
    public static final String RECORD = "record";
    public static final String START = "start";
    public static final String END = "end";
    public static final String LAST_MODIFIED = "lastModified";
    public static final String IS_FINAL = "isFinal";
    public static final String IS_SYNTHETIC = "isSynthetic";
    public static final String TYPE = "type";
    public static final String TARGET_FQN = "targetFqn";
    public static final String DATA_TYPE = "dataType";
    public static final String SOURCE_LABEL = "sourceLabel";
    public static final String SOURCE_ID = "sourceId";
    public static final String TEXT = "text";
    public static final String TEXT_HASH = "textHash";
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
    public static final String CALLER_SIGNATURES = "callerSignatures";
    public static final String OWNER_FQNS = "ownerFqns";
    public static final String IDS = "ids";
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
