// Port of the `impact` half of src/plugins/graph/mod.rs: `impact`/`impact_filtered`/`impact_tags`,
// `with_cochange`, `reverse_call_chain`, and the BFS (`impact_bfs`, `impact_walk_roots`, `WalkMemo`).
package rtok.plugins.graph

import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.RefGroupRow

/** A reached row with the position of the root it was found in (project scopes, T329.4.2). */
data class RootRow(val root: Int, val depth: Int, val path: String, val scope: String)

/** Answers per `(root, name)`, kept across walks over an unchanged index (T646). */
class WalkMemo {
    internal val ranked = HashMap<Pair<String, String>, ResolveHit>()
    internal val refs = HashMap<Pair<String, String>, List<RefGroupRow>>()
    internal val follows = HashMap<Pair<String, String>, List<Pair<String, String>>>()
}

object Impact {
    fun impact(cx: Ctx, root: String, name: String, depth: Int, to: String? = null) = impactFiltered(cx, root, name, depth, Filter.None, to)

    /** A non-empty `filter.path` keeps the reported lines in one subtree; the walk still crosses files outside it. */
    fun impactFiltered(cx: Ctx, root: String, name: String, depth: Int, filter: Filter, to: String? = null): String =
        Tools.textOr(cx, root, "impact", { Text.impact(cx, root, name, depth.toUInt(), filter, to) }) {
            Tools.lspOrTags(cx, root, "impact", listOf(name), {
                GraphCore.withStale(cx, root, Tools.lsp.impact(cx, root, name, depth, filter, to))
            }) { impactTags(cx, root, name, depth, filter, to) }
        }

    fun impactTags(cx: Ctx, root: String, name: String, depth: Int, filter: Filter, to: String?): String {
        GraphCore.indexFor(cx, root)
        val key = Index.canon(root)
        if (!to.isNullOrEmpty()) {
            val chains = cx.symbolPaths(key, to, name, depth.toUInt()).map(::reverseCallChain)
            if (chains.isEmpty()) return GraphCore.withStale(cx, root, "no path from $name to $to within depth $depth")
            Tally.hit(root, name, emptyList())
            return GraphCore.withStale(cx, root, Tools.cap(cx, annotate(cx, key, name, chains.joinToString("\n") + "\n")))
        }
        val ranked = Resolve.rankName(cx, key, name)
        val file = ranked.defPath ?: cx.symbolDefs(key, name).singleOrNull()?.a ?: name
        val rows = bfs(cx, key, name, depth).filter { filter.pathOk(it.path) }
            .sortedWith(compareBy<WalkRow> { it.depth }.thenBy { it.path }.thenBy { it.scope })
        if (rows.isEmpty()) {
            val text = annotate(cx, key, name, "nothing reaches $name${filter.scopeNote()}")
            return GraphCore.withStale(cx, root, withCochange(cx, root, file, text))
        }
        Tally.hit(root, name, rows.map { it.path })
        val text = StringBuilder()
        if (ranked.others > 0) text.append(Tools.otherDefsLine(name, ranked.others))
        val mark = cx.symbolDefs(key, name).size > 1
        var around = if (mark) Tools.ambiguousBanner(1) + Tools.markAmbiguousLines(text.toString()) else text.toString()
        Cochange.changesWith(cx, root, file)?.let { around += it }
        val budget = Budget.of(cx, filter.all).copy(overhead = cx.estimate(around, Class.Code), mark = mark)
        text.append(Blast.render(cx, rows, name, budget) { Rank.ranks(cx, key) })
        val capped = Tools.cap(cx, annotate(cx, key, name, text.toString()))
        return GraphCore.withStale(cx, root, withCochange(cx, root, file, capped))
    }

    private fun annotate(cx: Ctx, key: String, name: String, out: String) = Tools.flagAmbiguous(cx.symbolDefs(key, name).size, out)

    /** T371: the files that change with [file], after the cap so a long walk cannot hide them. */
    fun withCochange(cx: Ctx, root: String, file: String, text: String): String {
        val line = Cochange.changesWith(cx, root, file) ?: return text
        return (if (text.endsWith('\n')) text else "$text\n") + line + "\n"
    }

    /** `symbol_paths` walks callers; `impact --to` prints callee chains. */
    fun reverseCallChain(chain: String) = chain.split(" → ").reversed().joinToString(" → ")

    fun bfs(cx: Ctx, root: String, name: String, depth: Int, followImports: Boolean = true): List<WalkRow> =
        walkRoots(cx, listOf(root), name, depth, followImports).map { WalkRow(it.depth, it.path, it.scope) }

    fun walkRoots(cx: Ctx, roots: List<String>, name: String, depth: Int, followImports: Boolean, memo: WalkMemo = WalkMemo()): List<RootRow> {
        val seen = hashSetOf(name)
        var frontier = listOf(name)
        val out = ArrayList<RootRow>()
        for (d in 1..depth.coerceIn(1, 4)) {
            val next = ArrayList<String>()
            for (from in frontier) for ((i, root) in roots.withIndex()) {
                val key = root to from
                val allow = memo.ranked.getOrPut(key) { Resolve.rankName(cx, root, from) }
                for (g in memo.refs.getOrPut(key) { cx.symbolRefGroups(root, from) }) {
                    if (!allow.allows(g.path)) continue
                    if (g.kind.isEmpty()) out += RootRow(i, d, g.path, "")
                    else if (seen.add(g.kind)) { out += RootRow(i, d, g.path, g.kind); next += g.kind }
                }
                if (followImports) for ((path, def) in memo.follows.getOrPut(key) { cx.symbolImportFollow(root, from) }) {
                    if (seen.add(def)) { out += RootRow(i, d, path, def); next += def }
                }
            }
            frontier = next
            if (frontier.isEmpty()) break
        }
        return out
    }
}
