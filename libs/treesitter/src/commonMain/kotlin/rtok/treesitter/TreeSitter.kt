package rtok.treesitter

/** A row/column position; both 0-based, column in bytes (tree-sitter's `TSPoint`). */
data class Point(
    val row: Int,
    val column: Int,
)

/** A syntax node. Byte offsets index the UTF-8 source given to [Grammar.parse]. */
interface Node {
    val kind: String
    val isNamed: Boolean
    val isError: Boolean
    val hasError: Boolean
    val startByte: Int
    val endByte: Int
    val start: Point
    val end: Point
    val childCount: Int
    val namedChildCount: Int
    val parent: Node?

    fun child(index: Int): Node?

    fun namedChild(index: Int): Node?

    fun childByFieldName(field: String): Node?

    /** The tree-sitter S-expression of this subtree (debugging and tests). */
    fun sexp(): String

    fun children(): List<Node> = (0 until childCount).mapNotNull(::child)

    fun namedChildren(): List<Node> = (0 until namedChildCount).mapNotNull(::namedChild)

    fun sameAs(other: Node): Boolean = kind == other.kind && startByte == other.startByte && endByte == other.endByte
}

/** A parse tree; [close] frees it (nodes are invalid afterwards). */
interface Tree : AutoCloseable {
    val root: Node
}

class Capture(
    val index: Int,
    val name: String,
    val node: Node,
)

class QueryMatch(
    val pattern: Int,
    val captures: List<Capture>,
) {
    fun nodes(name: String): List<Node> = captures.filter { it.name == name }.map { it.node }
}

/** One argument of a query predicate: a capture reference (`@name`) or a string literal. */
sealed interface PredicateArg {
    data class CaptureRef(
        val name: String,
    ) : PredicateArg

    data class Literal(
        val value: String,
    ) : PredicateArg
}

/** `(#op! args…)` / `(#op? args…)` as written in the query. */
data class Predicate(
    val op: String,
    val args: List<PredicateArg>,
)

/** A compiled query. [rawMatches] applies no predicates; use [matches] for the standard text predicates. */
interface Query : AutoCloseable {
    val patternCount: Int
    val captureNames: List<String>

    fun predicates(pattern: Int): List<Predicate>

    /** Byte offset in the query source where [pattern] starts. */
    fun patternStart(pattern: Int): Int

    fun rawMatches(node: Node): Sequence<QueryMatch>
}

class QueryException(
    message: String,
) : Exception(message)

/** A language grammar with the queries bundled in its repository. */
interface Grammar {
    val name: String
    val tagsQuery: String?
    val localsQuery: String?
    val highlightsQuery: String?

    /** Parses UTF-8 [source]; `null` when the parser gives up (timeout/cancel). */
    fun parse(source: ByteArray): Tree?

    /** Compiles [source]; throws [QueryException] with the tree-sitter error kind and offset. */
    fun query(source: String): Query
}

/** The set of grammars an implementation provides. */
interface TreeSitter {
    val grammars: List<Grammar>

    fun grammar(name: String): Grammar? = grammars.firstOrNull { it.name == name }
}
