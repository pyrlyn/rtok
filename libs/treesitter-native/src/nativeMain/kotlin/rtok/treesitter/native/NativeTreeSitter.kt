@file:OptIn(ExperimentalForeignApi::class)

package rtok.treesitter.native

import cnames.structs.TSLanguage
import cnames.structs.TSQuery
import cnames.structs.TSTree
import kotlinx.cinterop.*
import platform.posix.free
import rtok.treesitter.*
import rtok.treesitter.native.c.*

/** Every grammar linked into librtok_ts.a, in the order of the generated grammar table. */
object NativeTreeSitter : TreeSitter {
    override val grammars: List<Grammar> by lazy {
        (0 until rtok_ts_grammar_count).map { i ->
            val row = rtok_ts_grammars[i]
            NativeGrammar(row.name!!.toKString(), row.language!!.invoke()!!, row.tags?.toKString(), row.locals?.toKString(), row.highlights?.toKString())
        }
    }
}

private class NativeGrammar(
    override val name: String,
    val language: CPointer<TSLanguage>,
    override val tagsQuery: String?,
    override val localsQuery: String?,
    override val highlightsQuery: String?,
) : Grammar {
    override fun parse(source: ByteArray): Tree? {
        val parser = ts_parser_new() ?: return null
        try {
            if (!ts_parser_set_language(parser, language)) return null
            val buf = if (source.isEmpty()) ByteArray(1) else source
            val tree = buf.usePinned { ts_parser_parse_string(parser, null, it.addressOf(0), source.size.toUInt()) }
            return tree?.let(::NativeTree)
        } finally {
            ts_parser_delete(parser)
        }
    }

    override fun query(source: String): Query =
        memScoped {
            val offset = alloc<UIntVar>()
            val error = alloc<TSQueryErrorVar>()
            val bytes = source.encodeToByteArray()
            val buf = if (bytes.isEmpty()) ByteArray(1) else bytes
            val q =
                buf.usePinned { ts_query_new(language, it.addressOf(0), bytes.size.toUInt(), offset.ptr, error.ptr) }
                    ?: throw QueryException(queryError(name, error.value, offset.value.toInt(), bytes))
            NativeQuery(q)
        }
}

private fun queryError(
    grammar: String,
    kind: Any,
    offset: Int,
    src: ByteArray,
): String {
    val near = src.decodeToString(offset.coerceIn(0, src.size), (offset + 40).coerceIn(0, src.size)).substringBefore('\n')
    return "$grammar query: ${kind.toString().removePrefix("TSQueryError")} error at byte $offset near `$near`"
}

internal class NativeTree(
    val ptr: CPointer<TSTree>,
) : Tree {
    private var closed = false
    override val root: Node get() = NativeNode(ts_tree_root_node(ptr), this)

    override fun close() {
        if (!closed) {
            closed = true
            ts_tree_delete(ptr)
        }
    }
}

private fun CValue<TSPoint>.point(): Point = useContents { Point(row.toInt(), column.toInt()) }

internal class NativeNode(
    val raw: CValue<TSNode>,
    val tree: NativeTree,
) : Node {
    private fun wrap(n: CValue<TSNode>): Node? = if (ts_node_is_null(n)) null else NativeNode(n, tree)

    override val kind: String get() = ts_node_type(raw)?.toKString() ?: ""
    override val isNamed: Boolean get() = ts_node_is_named(raw)
    override val isError: Boolean get() = ts_node_is_error(raw)
    override val hasError: Boolean get() = ts_node_has_error(raw)
    override val startByte: Int get() = ts_node_start_byte(raw).toInt()
    override val endByte: Int get() = ts_node_end_byte(raw).toInt()
    override val start: Point get() = ts_node_start_point(raw).point()
    override val end: Point get() = ts_node_end_point(raw).point()
    override val childCount: Int get() = ts_node_child_count(raw).toInt()
    override val namedChildCount: Int get() = ts_node_named_child_count(raw).toInt()
    override val parent: Node? get() = wrap(ts_node_parent(raw))

    override fun child(index: Int): Node? = wrap(ts_node_child(raw, index.toUInt()))

    override fun namedChild(index: Int): Node? = wrap(ts_node_named_child(raw, index.toUInt()))

    override fun childByFieldName(field: String): Node? = wrap(ts_node_child_by_field_name(raw, field, field.encodeToByteArray().size.toUInt()))

    override fun sexp(): String {
        val s = ts_node_string(raw) ?: return ""
        try {
            return s.toKString()
        } finally {
            free(s)
        }
    }

    override fun children(): List<Node> =
        memScoped {
            // A cursor walk is O(n) where repeated ts_node_child is O(n²) on wide nodes.
            val cursor = ts_tree_cursor_new(raw).getPointer(this)
            val out = ArrayList<Node>(childCount)
            try {
                if (ts_tree_cursor_goto_first_child(cursor)) {
                    do {
                        out += NativeNode(ts_tree_cursor_current_node(cursor), tree)
                    } while (ts_tree_cursor_goto_next_sibling(cursor))
                }
            } finally {
                ts_tree_cursor_delete(cursor)
            }
            out
        }
}

private class NativeQuery(
    val ptr: CPointer<TSQuery>,
) : Query {
    override val patternCount: Int = ts_query_pattern_count(ptr).toInt()
    override val captureNames: List<String> = (0 until ts_query_capture_count(ptr).toInt()).map { id -> name { ts_query_capture_name_for_id(ptr, id.toUInt(), it) } }

    private inline fun name(get: (CPointer<UIntVar>) -> CPointer<ByteVar>?): String =
        memScoped {
            val len = alloc<UIntVar>()
            val p = get(len.ptr) ?: return ""
            p.readBytes(len.value.toInt()).decodeToString()
        }

    override fun patternStart(pattern: Int): Int = ts_query_start_byte_for_pattern(ptr, pattern.toUInt()).toInt()

    override fun predicates(pattern: Int): List<Predicate> =
        memScoped {
            val count = alloc<UIntVar>()
            val steps = ts_query_predicates_for_pattern(ptr, pattern.toUInt(), count.ptr) ?: return emptyList()
            val out = ArrayList<Predicate>()
            var cur = ArrayList<PredicateArg>()
            var op: String? = null
            for (i in 0 until count.value.toInt()) {
                val step = steps[i]
                when (step.type) {
                    TSQueryPredicateStepType.TSQueryPredicateStepTypeDone -> {
                        op?.let { out += Predicate(it, cur) }
                        op = null
                        cur = ArrayList()
                    }

                    TSQueryPredicateStepType.TSQueryPredicateStepTypeCapture -> {
                        cur += PredicateArg.CaptureRef(captureNames[step.value_id.toInt()])
                    }

                    else -> {
                        val s = name { ts_query_string_value_for_id(ptr, step.value_id, it) }
                        if (op == null) op = s else cur += PredicateArg.Literal(s)
                    }
                }
            }
            out
        }

    override fun rawMatches(node: Node): Sequence<QueryMatch> =
        sequence {
            val n = node as NativeNode
            val cursor = ts_query_cursor_new() ?: return@sequence
            try {
                ts_query_cursor_exec(cursor, ptr, n.raw)
                val match = nativeHeap.alloc<TSQueryMatch>()
                try {
                    while (ts_query_cursor_next_match(cursor, match.ptr)) {
                        val caps =
                            (0 until match.capture_count.toInt()).map { i ->
                                val c = match.captures!![i]
                                Capture(c.index.toInt(), captureNames[c.index.toInt()], NativeNode(c.node.readValue(), n.tree))
                            }
                        yield(QueryMatch(match.pattern_index.toInt(), caps))
                    }
                } finally {
                    nativeHeap.free(match)
                }
            } finally {
                ts_query_cursor_delete(cursor)
            }
        }

    override fun close() = ts_query_delete(ptr)
}
