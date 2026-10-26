package rtok.treesitter

/**
 * Definition and reference tags from a grammar's `tags.scm` (+ `locals.scm`), with the semantics of
 * the `tree-sitter-tags` crate rtok (Rust) uses: `@name` + `@definition.<kind>`/`@reference.<kind>`,
 * `@doc` with `#strip!`/`#select-adjacent!`, `@ignore`, `#is-not? local` against `@local.*` scopes,
 * and one tag per name range (the earliest pattern wins), ordered by name range.
 */
class Tag(
    /** Byte range of the tagged node. */
    val range: IntRange,
    /** Byte range of the name node. */
    val nameRange: IntRange,
    /** Byte range of the name's line, leading whitespace trimmed, at most [MAX_LINE_LEN] bytes. */
    val lineRange: IntRange,
    val start: Point,
    val end: Point,
    val docs: String?,
    val isDefinition: Boolean,
    val syntaxType: String,
)

const val MAX_LINE_LEN = 180

class TagsConfiguration(
    val grammar: Grammar,
    tagsQuery: String,
    localsQuery: String = "",
) {
    private val query: Query = grammar.query(localsQuery + tagsQuery)
    private val tagsPatternIndex: Int = (0 until query.patternCount).firstOrNull { query.patternStart(it) >= localsQuery.length } ?: query.patternCount

    private class PatternInfo(
        val scopeInherits: Boolean,
        val nonLocal: Boolean,
        val stripRegex: Regex?,
        val adjacentTo: String?,
    )

    private val info: List<PatternInfo> =
        (0 until query.patternCount).map { p ->
            val preds = query.predicates(p)

            fun lit(
                pr: Predicate,
                i: Int,
            ) = (pr.args.getOrNull(i) as? PredicateArg.Literal)?.value
            PatternInfo(
                scopeInherits = preds.none { it.op == "set!" && lit(it, 0) == "local.scope-inherits" && lit(it, 1) == "false" },
                nonLocal = preds.any { it.op == "is-not?" && lit(it, 0) == "local" },
                stripRegex = preds.firstOrNull { it.op == "strip!" }?.let { lit(it, 1) }?.let(::Regex),
                adjacentTo = preds.firstOrNull { it.op == "select-adjacent!" }?.let { (it.args.getOrNull(1) as? PredicateArg.CaptureRef)?.name },
            )
        }

    private class Scope(
        val range: IntRange,
        val inherits: Boolean,
        val defs: MutableSet<String> = HashSet(),
    )

    fun generate(source: ByteArray): List<Tag> = grammar.withTree(source) { tree -> generate(tree, source) } ?: emptyList()

    fun generate(
        tree: Tree,
        source: ByteArray,
    ): List<Tag> {
        val scopes = ArrayDeque<Scope>().apply { addLast(Scope(0..source.size, false)) }
        val queue = HashMap<Pair<Int, Int>, Pair<Int, Tag>>()
        for (m in query.matches(tree.root, source)) {
            val first = m.captures.minOfOrNull { it.node.startByte } ?: continue
            while (scopes.size > 1 && scopes.last().range.last <= first) scopes.removeLast()
            if (m.pattern < tagsPatternIndex) {
                addLocals(m, source, scopes)
                continue
            }
            if (m.captures.any { it.name == "ignore" }) continue
            val name = m.captures.firstOrNull { it.name == "name" }?.node ?: continue
            val tagCap = m.captures.firstOrNull { it.name.startsWith("definition.") || it.name.startsWith("reference.") } ?: continue
            val pi = info[m.pattern]
            val nameText = source.decodeToString(name.startByte, name.endByte)
            if (pi.nonLocal && isLocal(scopes, nameText)) continue
            val tag =
                Tag(
                    range = tagCap.node.startByte until tagCap.node.endByte,
                    nameRange = name.startByte until name.endByte,
                    lineRange = lineRange(source, name.startByte, name.start.column),
                    start = tagCap.node.start,
                    end = tagCap.node.end,
                    docs = docs(m, pi, source),
                    isDefinition = tagCap.name.startsWith("definition."),
                    syntaxType = tagCap.name.substringAfter('.'),
                )
            val key = name.endByte to name.startByte
            val prev = queue[key]
            if (prev == null || m.pattern < prev.first) queue[key] = m.pattern to tag
        }
        return queue.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { it.value.second }
    }

    private fun addLocals(
        m: QueryMatch,
        source: ByteArray,
        scopes: ArrayDeque<Scope>,
    ) {
        for (c in m.captures) {
            when (c.name) {
                "local.scope" -> {
                    scopes.addLast(Scope(c.node.startByte..c.node.endByte, info[m.pattern].scopeInherits))
                }

                "local.definition" -> {
                    val text = source.decodeToString(c.node.startByte, c.node.endByte)
                    (scopes.lastOrNull { c.node.startByte in it.range } ?: scopes.last()).defs += text
                }
            }
        }
    }

    private fun isLocal(
        scopes: ArrayDeque<Scope>,
        name: String,
    ): Boolean {
        for (i in scopes.indices.reversed()) {
            if (name in scopes[i].defs) return true
            if (!scopes[i].inherits) return false
        }
        return false
    }

    private fun docs(
        m: QueryMatch,
        pi: PatternInfo,
        source: ByteArray,
    ): String? {
        var docNodes = m.nodes("doc")
        if (docNodes.isEmpty()) return null
        pi.adjacentTo?.let { adj ->
            val anchor = m.nodes(adj).firstOrNull()
            if (anchor != null) {
                val kept = ArrayList<Node>()
                var row = anchor.start.row
                for (d in docNodes.sortedByDescending { it.startByte }.filter { it.endByte <= anchor.startByte }) {
                    if (d.end.row + 1 < row) break
                    kept.add(0, d)
                    row = d.start.row
                }
                docNodes = kept
            }
        }
        if (docNodes.isEmpty()) return null
        return docNodes.joinToString("\n") { d ->
            val text = source.decodeToString(d.startByte, d.endByte)
            pi.stripRegex?.let { re -> text.lines().joinToString("\n") { re.replace(it, "") } } ?: text
        }
    }
}

/** The line holding [start], from its first non-blank byte to the newline, capped at [MAX_LINE_LEN] (UTF-8 safe). */
fun lineRange(
    source: ByteArray,
    start: Int,
    column: Int,
): IntRange {
    var from = (start - column).coerceAtLeast(0)
    while (from < source.size && (source[from] == ' '.code.toByte() || source[from] == '\t'.code.toByte())) from++
    var to = from
    val cap = minOf(source.size, from + MAX_LINE_LEN)
    while (to < cap && source[to] != '\n'.code.toByte() && source[to] != '\r'.code.toByte()) to++
    while (to < source.size && to > from && (source[to].toInt() and 0xC0) == 0x80) to--
    return from until to
}
