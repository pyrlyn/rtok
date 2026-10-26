// Port of the `explore` half of src/plugins/graph/mod.rs (T68.1): `explore_tokens`, `assemble_explore`,
// `assemble_named`, `explore`/`explore_tags` and the tags backend's `ExploreParts` (`TagsExplore`).
package rtok.plugins.graph

import rtok.plugin.sdk.Ctx

/** A name `explore` prints and the query token that resolved it (empty when the full-text fallback found it). */
data class Named(
    val name: String,
    val token: String,
)

private inline fun String.splitWhere(sep: (Char) -> Boolean): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    for (c in this) {
        if (sep(c)) {
            out += cur.toString()
            cur.clear()
        } else {
            cur.append(c)
        }
    }
    out += cur.toString()
    return out
}

object Explore {
    /** At most this many distinct names land in one answer: pairwise paths are O(names²). */
    const val MAX_NAMES = 10

    fun tokens(query: String): List<String> {
        val seen = HashSet<String>()
        return query.splitWhere { !it.isLetterOrDigit() && it != '_' }.filter { it.isNotEmpty() && seen.add(it) }.take(8)
    }

    private fun resolveNames(
        query: String,
        parts: ExploreParts,
    ): MutableList<Named> {
        val names = ArrayList<Named>()
        for (token in tokens(query)) {
            for (name in parts.resolve(token)) if (names.none { it.name == name }) names += Named(name, token)
            if (names.size >= MAX_NAMES) break
        }
        return names
    }

    /** One assembled answer plus the bytes the separate `symbol` + `impact` calls would have returned. */
    fun assemble(
        query: String,
        filter: Filter,
        parts: ExploreParts,
    ): Pair<String, Long> = assembleNamed(query, filter, parts, resolveNames(query, parts))

    fun assembleNamed(
        query: String,
        filter: Filter,
        parts: ExploreParts,
        names: List<Named>,
    ): Pair<String, Long> {
        if (names.isEmpty()) return "no symbols resolved for \"$query\"${filter.scopeNote()}" to 0L
        val counts = names.associate { it.name to parts.defCount(it.name) }
        val ambiguous = counts.values.count { it > 1 }
        val out = StringBuilder()
        if (ambiguous > 0) out.append(Tools.ambiguousBanner(ambiguous))
        var before = 0L
        val impact = ArrayList<String>()
        for ((name, token) in names) {
            var seen = Tally.mark()
            val defs = parts.defs(name)
            Tally.rename(seen, token)
            before += defs.encodeToByteArray().size
            out.append("= $name\n").append(defs)
            seen = Tally.mark()
            val (text, n) = parts.impact1(name)
            Tally.rewind(seen)
            before += text.encodeToByteArray().size
            impact += "$name ← $n${if (counts.getValue(name) > 1) " ?" else ""}"
        }
        out.append("paths:\n")
        var any = false
        for (a in names) {
            for (b in names) {
                if (a.name != b.name) {
                    for (chain in parts.paths(a.name, b.name)) {
                        out.append(chain).append('\n')
                        any = true
                    }
                }
            }
        }
        if (!any) out.append("none\n")
        out.append("impact:\n")
        for (line in impact) out.append(line).append('\n')
        return out.toString() to before
    }

    /** `explore(query, path?)`: one call answers what `symbol` + `impact` + caller-walking did in three to five. */
    fun explore(
        cx: Ctx,
        root: String,
        query: String,
        filter: Filter = Filter.None,
    ): String =
        Tools.textOr(cx, root, "explore", { assemble(query, filter, Text.Explore(cx, root, filter)).first }) {
            Tools.lspOrTags(cx, root, "explore", tokens(query), { Tools.lsp.explore(cx, root, query, filter) }) { exploreTags(cx, root, query, filter) }
        }

    fun exploreTags(
        cx: Ctx,
        root: String,
        query: String,
        filter: Filter,
    ): String {
        GraphCore.indexFor(cx, root)
        val parts = TagsExplore(cx, root, filter)
        val names = resolveNames(query, parts)
        // No name resolved: search signature and doc too (T474); an FTS error keeps the "no symbols" line.
        if (names.isEmpty()) {
            runCatching { cx.symbolFts(parts.key, query, MAX_NAMES.toLong()) }.getOrNull()?.let { hits ->
                for (h in hits) {
                    if (names.none { it.name == h.name }) names += Named(h.name, "")
                    if (names.size >= MAX_NAMES) break
                }
            }
        }
        val (text, before) = assembleNamed(query, filter, parts, names)
        return GraphCore.withStale(cx, root, Tools.capKind(cx, text, before.toULong(), "explore"))
    }
}

/** The tree-sitter-tags backend's pieces: every query answered from indexed rows, bodies read like `symbol`. */
class TagsExplore(
    private val cx: Ctx,
    private val root: String,
    private val filter: Filter,
    private val label: String = "",
) : ExploreParts {
    val key: String = Index.canon(root)

    override fun resolve(token: String): List<String> = if (cx.symbolDefs(key, token).isNotEmpty()) listOf(token) else cx.symbolNamePrefix(key, token, 5)

    override fun defs(name: String): String {
        val ranked = Resolve.rankName(cx, key, name)
        var rows = cx.symbolDefs(key, name).filter { filter.pathOk(it.a) }
        ranked.defPath?.let { p -> rows.filter { it.a == p }.takeIf { it.isNotEmpty() }?.let { rows = it } }
        if (rows.isEmpty()) return "no definition of $name${filter.scopeNote()}\n"
        Tally.hit(root, name, rows.map { it.a })
        var text = Tools.defsText(cx, root, name, rows, cx.symbolCallees(key, name), Tag(prefix = label))
        if (ranked.others > 0) text += Tools.otherDefsLine(name, ranked.others)
        return text
    }

    override fun defCount(name: String) = cx.symbolDefs(key, name).size

    override fun paths(
        a: String,
        b: String,
    ) = cx.symbolPaths(key, a, b, 3u)

    override fun impact1(name: String): Pair<String, Int> {
        val ranked = Resolve.rankName(cx, key, name)
        val rows =
            Impact
                .bfs(cx, key, name, 1)
                .filter { filter.pathOk(it.path) }
                .sortedWith(compareBy<WalkRow> { it.depth }.thenBy { it.path }.thenBy { it.scope })
        if (rows.isEmpty()) return "nothing reaches $name${filter.scopeNote()}" to 0
        var text = Blast.linesText(rows)
        if (ranked.others > 0) text += Tools.otherDefsLine(name, ranked.others)
        return text to rows.size
    }
}
