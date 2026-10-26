// Grammar selection and tags queries for the read/graph plugins (src/plugins/read/outline.rs
// `grammar_for_ext`, `config_for_grammar`, the extra query constants). Grammars come from
// rtok.treesitter; this file only maps file suffixes to grammar names and composes queries.
package rtok.plugins.read

import rtok.treesitter.Grammar
import rtok.treesitter.TagsConfiguration
import rtok.treesitter.TreeSitter
import rtok.treesitter.native.NativeTreeSitter

object Languages {
    /** The tree-sitter implementation used by rtok (one place to swap it). */
    val treeSitter: TreeSitter get() = NativeTreeSitter

    /** File suffix (and the Rust config grammar keys) → tree-sitter grammar name. */
    private val byKey: Map<String, String> = buildMap {
        fun put(grammar: String, vararg keys: String) { for (k in keys) this[k] = grammar }
        put("rust", "rs", "rust"); put("typescript", "ts", "mts", "cts", "typescript"); put("tsx", "tsx")
        put("javascript", "js", "mjs", "cjs", "jsx", "javascript"); put("python", "py", "pyi", "python")
        put("dart", "dart"); put("c", "c", "h"); put("go", "go"); put("java", "java")
        put("kotlin", "kt", "kts", "kotlin"); put("swift", "swift"); put("c_sharp", "cs", "csharp", "c_sharp")
        put("ruby", "rb", "ruby"); put("php", "php")
        // Parse-only grammars (stripped mode, node walks): no tags query bundled or composed.
        put("cpp", "cc", "cpp", "cxx", "hpp", "hh", "hxx"); put("scala", "scala", "sc"); put("haskell", "hs")
        put("julia", "jl"); put("ocaml", "ml"); put("ocaml_interface", "mli"); put("bash", "sh", "bash")
        put("css", "css"); put("html", "html", "htm"); put("json", "json"); put("verilog", "v", "sv")
        put("agda", "agda"); put("fluent", "ftl"); put("ql", "ql"); put("ql_dbscheme", "dbscheme")
        put("embedded_template", "erb", "ejs"); put("regex", "regex"); put("jsdoc", "jsdoc")
    }

    /** Grammars that produce tags (the outline/graph languages). */
    private val tagged = setOf("rust", "typescript", "tsx", "javascript", "python", "dart", "c", "go", "java", "kotlin", "swift", "c_sharp", "ruby", "php")

    fun grammarName(key: String?): String? = key?.let(byKey::get)

    fun grammar(key: String?): Grammar? = grammarName(key)?.let(treeSitter::grammar)

    /** True when [grammar] (a suffix or grammar key from `[plugins.graph.extensions]`) has tags in this build. */
    fun hasTags(key: String?): Boolean = grammarName(key) in tagged

    /** Tags and locals query sources rtok compiles for [grammarName]. */
    fun queries(grammarName: String): Pair<String, String>? {
        val g = treeSitter.grammar(grammarName) ?: return null
        val upstream = g.tagsQuery.orEmpty()
        val locals = g.localsQuery.orEmpty()
        return when (grammarName) {
            "rust" -> upstream + RUST_SCOPED_CALL + RUST_IMPORT + RUST_EXTRA_REF to ""
            "typescript", "tsx" -> upstream + JS_IMPORT + TS_CALL_TYPE_REF to locals
            "javascript" -> upstream + JS_IMPORT to locals
            "python" -> upstream + PYTHON_IMPORT to ""
            "dart" -> upstream + DART_IMPORT to locals
            "go" -> upstream + GO_IMPORT to ""
            "c", "java", "swift", "php" -> upstream to ""
            "ruby" -> upstream to locals
            "kotlin" -> KOTLIN_TAGS to ""
            "c_sharp" -> CSHARP_TAGS to ""
            else -> null
        }
    }

    private val compiled = HashMap<String, Result<TagsConfiguration>>()

    /** The tags configuration for [grammarName], compiled once per process (T35.1); a failure is kept too. */
    fun tags(grammarName: String): Result<TagsConfiguration>? {
        if (grammarName !in tagged) return null
        return compiled.getOrPut(grammarName) {
            runCatching {
                val (tags, locals) = queries(grammarName) ?: error("no grammar $grammarName")
                TagsConfiguration(treeSitter.grammar(grammarName)!!, tags, locals)
            }.recoverCatching { throw IllegalStateException("tags query: ${it.message}") }
        }
    }
}

/** The upstream Rust tags query only sees bare and method calls; path-qualified calls need this too (T8.2). */
internal const val RUST_SCOPED_CALL = """
(call_expression
    function: (scoped_identifier
        name: (identifier) @name)) @reference.call
"""

/** `use` as `kind = import` (T68.6); whole-clause captures feed `expandImport`, inner ones the last segment (T368). */
internal const val RUST_IMPORT = """
(use_declaration argument: (identifier) @name) @reference.import
(use_declaration argument: (scoped_identifier) @name) @reference.import
(use_declaration argument: (scoped_identifier name: (identifier) @name)) @reference.import
(use_declaration argument: (use_as_clause) @name) @reference.import
(use_declaration argument: (use_as_clause path: (identifier) @name)) @reference.import
(use_declaration argument: (use_as_clause path: (scoped_identifier name: (identifier) @name))) @reference.import
(use_declaration argument: (use_list) @name) @reference.import
(use_declaration argument: (scoped_use_list) @name) @reference.import
(use_list (identifier) @name) @reference.import
(use_list (scoped_identifier name: (identifier) @name)) @reference.import
(use_list (use_as_clause path: (identifier) @name)) @reference.import
(use_list (use_as_clause path: (scoped_identifier name: (identifier) @name))) @reference.import
(use_declaration argument: (use_wildcard) @name) @reference.import
(use_wildcard (identifier) @name) @reference.import
(use_wildcard (scoped_identifier name: (identifier) @name)) @reference.import
"""

/** `import` / `require` last path segment (T68.6). Shared by JS and TS/TSX. */
internal const val JS_IMPORT = """
(import_statement source: (string) @name) @reference.import
(import_specifier name: (identifier) @name) @reference.import
(call_expression
    function: (identifier) @doc
    arguments: (arguments (string) @name)
    (#eq? @doc "require")) @reference.import
"""

/** `import` / `from … import` last path segment (T68.6). */
internal const val PYTHON_IMPORT = """
(import_statement name: (dotted_name) @name) @reference.import
(import_statement name: (aliased_import name: (dotted_name) @name)) @reference.import
(import_from_statement name: (dotted_name) @name) @reference.import
(import_from_statement name: (aliased_import name: (dotted_name) @name)) @reference.import
(import_from_statement module_name: (dotted_name) @name) @reference.import
"""

/** `import "path"` last path segment (T68.6). */
internal const val GO_IMPORT = """
(import_spec path: (interpreted_string_literal) @name) @reference.import
(import_spec path: (raw_string_literal) @name) @reference.import
"""

/** `import 'uri'` last path segment (T68.6). Dart grammar master has no `uri:` field (Rust used crate 0.2). */
internal const val DART_IMPORT = """
(import_specification [(configurable_uri) (uri)] @name) @reference.import
"""

/** tree-sitter-kotlin ships no tags query (T52.2). */
internal const val KOTLIN_TAGS = """
(class_declaration name: (identifier) @name) @definition.class
(object_declaration name: (identifier) @name) @definition.class
(function_declaration name: (identifier) @name) @definition.function
"""

/** tree-sitter-c-sharp gates its tags query behind a build flag in Rust; same patterns here. */
internal const val CSHARP_TAGS = """
(class_declaration name: (identifier) @name) @definition.class
(interface_declaration name: (identifier) @name) @definition.interface
(method_declaration name: (identifier) @name) @definition.method
(namespace_declaration name: (identifier) @name) @definition.module
"""

/** T52.5 / T663: type positions, path segments and calls inside macro token trees. */
internal const val RUST_EXTRA_REF = """
(type_identifier) @name @reference.type
(scoped_type_identifier
    path: (identifier) @name) @reference.type
(scoped_identifier
    name: (identifier) @name) @reference.call
(scoped_identifier
    path: (identifier) @name) @reference.call
(token_tree
    (identifier) @name @reference.call
    .
    (token_tree))
"""

/** T52.5: calls, member calls, member constructions and generic type arguments for TypeScript. */
internal const val TS_CALL_TYPE_REF = """
(call_expression
    function: (identifier) @name) @reference.call
(call_expression
    function: (member_expression
        property: (property_identifier) @name)) @reference.call
(call_expression
    function: (member_expression
        object: (member_expression
            property: (property_identifier) @name))) @reference.call
(new_expression
    constructor: (member_expression
        property: (property_identifier) @name)) @reference.class
(type_identifier) @name @reference.type
(nested_type_identifier
    module: (identifier) @name) @reference.type
"""
