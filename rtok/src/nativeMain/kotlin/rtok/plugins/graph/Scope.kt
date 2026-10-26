// Port of src/plugins/graph/scope.rs (T329.4-5): graph answers over a project scope, the selected project and
// what it links to. A scope of one answers byte for byte like the single-project tools.
package rtok.plugins.graph

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.plugin.sdk.Callee
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.RefGroupRow
import rtok.plugin.sdk.SymDef
import rtok.plugins.read.walkRootOk
import rtok.store.Store
import rtok.store.projectByRoot
import rtok.store.projectScope

/** One project of a scope: the label its rows carry and the root its index is keyed by. */
data class Member(
    val name: String,
    val root: String,
)

object Scope {
    /** The scope of a call: [project] (id or path), else the cwd's project; an unregistered directory is a scope of one. */
    fun resolve(
        store: Store,
        project: String?,
        cwd: String,
    ): List<Member> {
        val start =
            if (!project.isNullOrEmpty()) {
                Projects.resolve(store, project)
            } else {
                store.projectByRoot(cwd)
                    ?: return listOf(Member(cwd.trimEnd('/').substringAfterLast('/'), cwd))
            }
        val scope = store.projectScope(start.id)
        if (scope.isEmpty()) throw IllegalStateException("${start.root} no longer exists; remove it or restore the directory")
        return scope.map { Member(it.displayName(), it.root) }
    }

    /** Runs [f] per member; the first member's failure is the caller's, a linked one is skipped with a note. */
    fun <T> fanOut(
        scope: List<Member>,
        f: (Member) -> T,
    ): Pair<List<Pair<Member, T>>, String> {
        val done = ArrayList<Pair<Member, T>>()
        val notes = StringBuilder()
        for ((i, m) in scope.withIndex()) {
            val r = runCatching { f(m) }
            r.onSuccess { done += m to it }.onFailure { e ->
                if (i == 0) throw e
                notes.append("[${m.name}] skipped: ${e.message ?: e}\n")
            }
        }
        return done to notes.toString()
    }

    private fun <T> banners(
        cx: Ctx,
        done: List<Pair<Member, T>>,
        notes: String,
    ): String =
        buildString {
            for ((m, _) in done) {
                val b = GraphCore.staleBanner(cx, m.root)
                if (b.isNotEmpty()) append("[${m.name}] $b")
            }
            append(notes)
        }

    private fun lspNote(scope: List<Member>) = "note: backend = \"lsp\" answers [${scope[0].name}] only; ${scope.size - 1} linked projects not searched\n"

    private enum class Plan { Tags, LspFirst, PerProject }

    private fun plan(
        cx: Ctx,
        scope: List<Member>,
    ): Plan {
        fun usable(m: Member): Boolean {
            val name = GraphCore.backendName(cx, m.root)
            return Mode.named(name) == Mode.Auto && Capability.serverReady(cx, m.root, name, Tools.lsp::probe)
        }
        return when {
            scope.any { Text.applies(cx, it.root, Tools.lsp::probe) || usable(it) } -> Plan.PerProject
            GraphCore.modeOf(cx, scope[0].root) == Mode.Lsp -> Plan.LspFirst
            else -> Plan.Tags
        }
    }

    private fun tagsModes(
        cx: Ctx,
        scope: List<Member>,
    ) = scope.filter { GraphCore.modeOf(cx, it.root) == Mode.Auto }.joinToString("") { "[${it.name}] (tags)\n" }

    /** Each project answers through its own door, each part labelled; a silent project is left out unless all are. */
    private fun perProject(
        cx: Ctx,
        scope: List<Member>,
        ask: (Member) -> String,
    ): String {
        val (done, notes) =
            fanOut(scope) {
                walkable(it)
                ask(it)
            }
        val said = done.filter { !GraphCore.lspNoneAnswer(GraphCore.withoutModeLine(it.second)) }.ifEmpty { done.take(1) }
        val body = StringBuilder()
        for ((m, text) in said) {
            body.append(label(m)).append(text)
            if (!text.endsWith('\n')) body.append('\n')
        }
        return capped(cx, notes, body.toString())
    }

    /** Notes and banners count against the one cap (T329.5). */
    private fun capped(
        cx: Ctx,
        head: String,
        body: String,
    ) = Tools.cap(cx, head + body)

    fun label(m: Member) = "[${m.name}] "

    fun walkable(m: Member) = walkRootOk(m.root)

    /** Several names in one call; a single name is [symbol] byte for byte. */
    fun symbols(
        cx: Ctx,
        scope: List<Member>,
        names: List<String>,
        filter: Filter,
    ): String {
        if (names.size <= 1) return symbol(cx, scope, names.firstOrNull().orEmpty(), filter)
        val out = StringBuilder()
        for (name in names) {
            val body = symbol(cx, scope, name, filter)
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
            out
                .append("= ")
                .append(name)
                .append('\n')
                .append(body)
            if (!body.endsWith('\n')) out.append('\n')
        }
        return Tools.cap(cx, out.toString())
    }

    fun symbolId(
        cx: Ctx,
        scope: List<Member>,
        id: String,
    ): String {
        scope.singleOrNull()?.let {
            walkable(it)
            return Tools.symbolById(cx, it.root, id)
        }
        val out = StringBuilder()
        for (m in scope) {
            walkable(m)
            val text = Tools.symbolById(cx, m.root, id)
            if (text.startsWith("no definition of")) continue
            out.append(label(m)).append(text)
        }
        return if (out.isEmpty()) "no definition of $id" else out.toString()
    }

    fun symbol(
        cx: Ctx,
        scope: List<Member>,
        name: String,
        filter: Filter,
    ): String {
        scope.singleOrNull()?.let {
            walkable(it)
            return Tools.symbol(cx, it.root, name, filter)
        }
        when (plan(cx, scope)) {
            Plan.PerProject -> {
                return perProject(cx, scope) { Tools.symbol(cx, it.root, name, filter) }
            }

            Plan.LspFirst -> {
                walkable(scope[0])
                return Tools.lsp.symbol(cx, scope[0].root, name, filter) + lspNote(scope)
            }

            Plan.Tags -> {}
        }
        val (done, notes) =
            fanOut(scope) { m ->
                walkable(m)
                GraphCore.indexFor(cx, m.root)
                val key = Index.canon(m.root)
                val rows = cx.symbolDefs(key, name).filter { filter.pathOk(it.a) && filter.kindOk(it.kind) }
                rows to (if (rows.isEmpty()) emptyList<Callee>() else cx.symbolCallees(key, name))
            }
        val head = tagsModes(cx, scope) + banners(cx, done, notes)
        val found = done.filter { it.second.first.isNotEmpty() }
        if (found.isEmpty()) return "${head}no definition of $name${filter.scopeNote()}"
        val many = found.size > 1
        val body = StringBuilder(if (many) Tools.ambiguousBanner(1) else "")
        for ((m, rc) in found) {
            val (rows, callees) = rc
            Tally.hit(m.root, name, rows.map { it.a })
            body.append(Tools.defsText(cx, m.root, name, rows, callees, Tag(label(m), if (many) " ?" else "")))
        }
        return capped(cx, head, body.toString())
    }

    fun callers(
        cx: Ctx,
        scope: List<Member>,
        name: String,
        filter: Filter,
    ): String {
        scope.singleOrNull()?.let {
            walkable(it)
            return Tools.callers(cx, it.root, name, filter)
        }
        when (plan(cx, scope)) {
            Plan.PerProject -> {
                return perProject(cx, scope) { Tools.callers(cx, it.root, name, filter) }
            }

            Plan.LspFirst -> {
                walkable(scope[0])
                return Tools.lsp.callers(cx, scope[0].root, name, filter) + lspNote(scope)
            }

            Plan.Tags -> {}
        }
        val (done, notes) =
            fanOut(scope) { m ->
                walkable(m)
                GraphCore.indexFor(cx, m.root)
                val key = Index.canon(m.root)
                cx.symbolRefGroups(key, name).filter { filter.pathOk(it.path) } to cx.symbolDefs(key, name).size
            }
        val head = tagsModes(cx, scope) + banners(cx, done, notes)
        val defs = done.sumOf { it.second.second }
        val body = StringBuilder()
        for ((m, rn) in done) {
            val rows: List<RefGroupRow> = rn.first
            if (rows.isNotEmpty()) Tally.hit(m.root, name, rows.map { it.path })
            for (r in rows) body.append("${label(m)}${r.path}${if (r.kind.isEmpty()) "" else "  ${r.kind}"} ×${r.count} (L${r.line})\n")
        }
        if (body.isEmpty()) return head + Tools.flagAmbiguous(defs, "no references to $name${filter.scopeNote()}")
        return capped(cx, head, Tools.flagAmbiguous(defs, body.toString()))
    }

    /** One walk asked of every member's index, so a change in C reaches its callers in B and then A. */
    fun impact(
        cx: Ctx,
        scope: List<Member>,
        name: String,
        depth: Int,
        filter: Filter,
        to: String?,
    ): String {
        scope.singleOrNull()?.let {
            walkable(it)
            return Impact.impactFiltered(cx, it.root, name, depth, filter, to)
        }
        when (plan(cx, scope)) {
            Plan.PerProject -> {
                return perProject(cx, scope) { Impact.impactFiltered(cx, it.root, name, depth, filter, to) }
            }

            Plan.LspFirst -> {
                walkable(scope[0])
                val root = scope[0].root
                return GraphCore.withStale(cx, root, Tools.lsp.impact(cx, root, name, depth, filter, to)) + lspNote(scope)
            }

            Plan.Tags -> {}
        }
        val target = to?.takeIf { it.isNotEmpty() }
        val (done, notes) =
            fanOut(scope) { m ->
                walkable(m)
                GraphCore.indexFor(cx, m.root)
                val key = Index.canon(m.root)
                Triple(key, if (target != null) cx.symbolPaths(key, target, name, depth.toUInt()) else emptyList(), cx.symbolDefs(key, name).size)
            }
        val head = tagsModes(cx, scope) + banners(cx, done, notes)
        val defs = done.sumOf { it.second.third }
        val body: String =
            if (target != null) {
                val b = StringBuilder()
                for ((m, t) in done) {
                    if (t.second.isNotEmpty()) Tally.hit(m.root, name, emptyList())
                    for (c in t.second) b.append(label(m)).append(Impact.reverseCallChain(c)).append('\n')
                }
                if (b.isEmpty()) return "${head}no path from $name to $target within depth $depth"
                b.toString()
            } else {
                val rows = walkRows(cx, done.map { label(it.first) }, done.map { it.second.first }, name, depth, filter)
                if (rows.isEmpty()) return head + Tools.flagAmbiguous(defs, "nothing reaches $name${filter.scopeNote()}")
                val budget = Budget.of(cx, filter.all).copy(overhead = cx.estimate(head + Tools.ambiguousBanner(1), Class.Code), mark = defs > 1)
                Blast.render(cx, rows, name, budget) { emptyMap() }
            }
        return capped(cx, head, Tools.flagAmbiguous(defs, body))
    }

    /** The scoped walk's rows, the member label before each path; the selected project's rows lead within a depth. */
    internal fun walkRows(
        cx: Ctx,
        labels: List<String>,
        keys: List<String>,
        name: String,
        depth: Int,
        filter: Filter,
    ): List<WalkRow> {
        val rows =
            Impact
                .walkRoots(cx, keys, name, depth, true)
                .filter { filter.pathOk(it.path) }
                .sortedWith(compareBy<RootRow> { it.depth }.thenBy { it.root }.thenBy { it.path }.thenBy { it.scope })
        for (r in rows) Tally.hit(keys[r.root], name, listOf(r.path))
        return rows.map { WalkRow(it.depth, labels[it.root] + it.path, it.scope) }
    }

    fun explore(
        cx: Ctx,
        scope: List<Member>,
        query: String,
        filter: Filter,
    ): String {
        scope.singleOrNull()?.let {
            walkable(it)
            return Explore.explore(cx, it.root, query, filter)
        }
        when (plan(cx, scope)) {
            Plan.PerProject -> {
                return perProject(cx, scope) { Explore.explore(cx, it.root, query, filter) }
            }

            Plan.LspFirst -> {
                walkable(scope[0])
                return Tools.lsp.explore(cx, scope[0].root, query, filter) + lspNote(scope)
            }

            Plan.Tags -> {}
        }
        val (done, notes) =
            fanOut(scope) {
                walkable(it)
                GraphCore.indexFor(cx, it.root)
            }
        val head = tagsModes(cx, scope) + banners(cx, done, notes)
        val labels = done.map { label(it.first) }
        val all = Scoped(cx, filter, done.zip(labels).map { (d, l) -> TagsExplore(cx, d.first.root, filter, l) }, labels)
        val (text, before) = Explore.assemble(query, filter, all)
        return Tools.capKind(cx, head + text, before.toULong(), "explore")
    }

    /** `ExploreParts` over several members' [TagsExplore]. */
    private class Scoped(
        val cx: Ctx,
        val filter: Filter,
        val parts: List<TagsExplore>,
        val labels: List<String>,
    ) : ExploreParts {
        override fun resolve(token: String): List<String> {
            if (defCount(token) > 0) return listOf(token)
            val names = ArrayList<String>()
            for (p in parts) for (n in p.resolve(token)) if (n !in names) names += n
            return names.take(5)
        }

        override fun defs(name: String): String {
            val out = StringBuilder()
            for (p in parts) if (p.defCount(name) > 0) out.append(p.defs(name))
            return if (out.isEmpty()) parts[0].defs(name) else out.toString()
        }

        override fun defCount(name: String) = parts.sumOf { it.defCount(name) }

        override fun paths(
            a: String,
            b: String,
        ) = parts.zip(labels).flatMap { (p, l) -> p.paths(a, b).map { l + it } }

        override fun impact1(name: String): Pair<String, Int> {
            val rows = walkRows(cx, labels, parts.map { it.key }, name, 1, filter)
            if (rows.isEmpty()) return "nothing reaches $name${filter.scopeNote()}" to 0
            return Blast.linesText(rows) to rows.size
        }
    }

    /** Index of the project that holds [path]: the first one that has it, else the first. */
    fun holder(
        scope: List<Member>,
        path: String,
    ): Int {
        fun canon(q: String) = rtok.sys.canonicalize(q) ?: q
        val i =
            scope.indexOfFirst { m ->
                if (path.startsWith("/")) {
                    canon(path).let { r ->
                        val root = canon(m.root)
                        r == root || r.startsWith("$root/")
                    }
                } else {
                    rtok.sys.exists("${m.root}/$path")
                }
            }
        return if (i < 0) 0 else i
    }

    fun outline(
        cx: Ctx,
        scope: List<Member>,
        path: String,
    ) = Outline.outlineIn(cx, scope[holder(scope, path)].root, path)

    /** Dead rows per project; a definition only a linked project references stays live. */
    private fun deadByProject(
        cx: Ctx,
        scope: List<Member>,
        rowsOf: (Ctx, String) -> List<DeadRow>,
    ): Pair<List<Pair<Member, List<DeadRow>>>, String> {
        val texty = scope.filter { Text.applies(cx, it.root, Tools.lsp::probe) }.map { it.root }.toSet()
        val (done0, notes0) =
            fanOut(scope) { m ->
                walkable(m)
                if (m.root in texty) emptyList() else rowsOf(cx, m.root)
            }
        var notes = notes0
        for (m in scope.filter { it.root in texty }) notes += "${label(m)}dead: ${Text.UNAVAILABLE}\n"
        val keys = done0.map { Index.canon(it.first.root) }
        if (keys.size <= 1) return done0 to notes
        val done =
            done0.mapIndexed { i, (m, rows) ->
                val names = rows.map { it.name }.toSortedSet().toList()
                val used = HashSet<String>()
                for ((j, k) in keys.withIndex()) if (j != i) used += cx.symbolReferencedNames(k, names)
                m to rows.filter { it.name !in used }
            }
        return done to notes
    }

    private fun <T : Comparable<T>> List<T>.toSortedSet() = sorted().distinct()

    /** The Graph page's dead rows over a scope, read from the stores as they stand. */
    fun deadPageRows(
        cx: Ctx,
        scope: List<Member>,
    ): Pair<List<Pair<String, DeadRow>>, String> {
        val (done, notes) = deadByProject(cx, scope, Dead::candidates)
        return done.flatMap { (m, rows) -> rows.map { m.name to it } } to notes
    }

    fun pendingPagePaths(
        cx: Ctx,
        scope: List<Member>,
    ): List<String> = scope.flatMap { m -> runCatching { GraphCore.pendingPaths(cx, m.root) }.getOrDefault(emptyList()).map { label(m) + it } }

    /** `graph dead --json`: one project prints plain rows, several add the `project` of each. */
    fun deadJson(
        cx: Ctx,
        scope: List<Member>,
    ): String {
        scope.singleOrNull()?.let {
            walkable(it)
            return Projects.pretty.encodeToString(ListSerializer(DeadRow.serializer()), Dead.rows(cx, it.root))
        }
        val (done, _) = deadByProject(cx, scope, Dead::rows)
        // serde_json without preserve_order writes keys sorted.
        return Projects.pretty.encodeToString(
            JsonElement.serializer(),
            buildJsonArray {
                for ((m, rows) in done) {
                    for (r in rows) {
                        add(
                            buildJsonObject {
                                put("kind", r.kind)
                                put("line", r.line)
                                put("name", r.name)
                                put("path", r.path)
                                put("project", m.name)
                            },
                        )
                    }
                }
            },
        )
    }

    private fun deadLines(
        done: List<Pair<Member, List<DeadRow>>>,
        tagged: Boolean,
        keep: (DeadRow) -> Boolean = { true },
    ) = buildString {
        for ((m, rows) in done) for (r in rows.filter(keep)) append("${if (tagged) label(m) else ""}${r.path}:${r.line} ${r.kind} ${r.name}\n")
    }

    fun dead(
        cx: Ctx,
        scope: List<Member>,
    ): String {
        scope.singleOrNull()?.let {
            walkable(it)
            return Dead.dead(cx, it.root)
        }
        val (done, notes) = deadByProject(cx, scope, Dead::rows)
        val body = deadLines(done, true)
        if (body.isEmpty()) {
            if (done.all { Text.applies(cx, it.first.root, Tools.lsp::probe) }) return notes
            return "${notes}no dead code in [${done.joinToString("], [") { it.first.name }}]"
        }
        return capped(cx, notes, body)
    }

    /** `callers` with no name (T665): the unreferenced definitions, narrowed by `path` and `kind`. */
    fun deadFiltered(
        cx: Ctx,
        scope: List<Member>,
        filter: Filter,
    ): String {
        val (done, notes) = deadByProject(cx, scope, Dead::rows)
        val body = deadLines(done, scope.size > 1) { filter.pathOk(it.path) && filter.kindOk(it.kind) }
        if (body.isEmpty()) {
            if (done.all { Text.applies(cx, it.first.root, Tools.lsp::probe) }) return notes
            return "${notes}no dead code${filter.scopeNote()}"
        }
        return capped(cx, notes, body)
    }

    /** Tests that reach the changes: `changed[i]` are the changed paths of `scope[i]`. */
    fun affected(
        cx: Ctx,
        scope: List<Member>,
        changed: List<List<String>>,
        depth: Int,
        json: Boolean,
    ): String {
        scope.singleOrNull()?.let {
            walkable(it)
            return Affected.fromPaths(cx, it.root, changed[0], depth, json)
        }
        var at = 0
        val (done, notes) =
            fanOut(scope) { m ->
                // Taken before anything can fail: a skipped project must not shift the rest.
                val paths = changed[at++]
                walkable(m)
                GraphCore.indexFor(cx, m.root)
                val key = Index.canon(m.root)
                val (hits, starts) = Affected.changedStarts(cx, m.root, key, paths)
                Triple(key, hits, starts)
            }
        val keys = done.map { it.second.first }
        val hits = done.map { HashSet(it.second.second) }
        val starts = done.flatMap { it.second.third }.toSortedSet()
        for (name in starts) for (r in Impact.walkRoots(cx, keys, name, depth, true)) if (Dead.isTestPath(r.path)) hits[r.root] += r.path to Affected.viaOf(name, r.scope)
        for ((d, h) in done.zip(hits)) Affected.tallyTests(d.first.root, h)
        if (json) {
            val projects =
                buildJsonArray {
                    for ((d, h) in done.zip(hits)) {
                        if (h.isNotEmpty()) {
                            add(
                                buildJsonObject {
                                    put("project", d.first.name)
                                    put("root", d.first.root)
                                    put("tests", Affected.testsJson(h))
                                },
                            )
                        }
                    }
                }
            return (
                if (projects.isEmpty()) {
                    buildJsonObject {
                        put("message", Affected.EMPTY)
                        put("projects", projects)
                    }
                } else {
                    buildJsonObject { put("projects", projects) }
                }
            ).toString()
        }
        if (hits.all { it.isEmpty() }) return notes + Affected.EMPTY
        val body = buildString { for ((d, h) in done.zip(hits)) if (h.isNotEmpty()) append("[${d.first.name}]\n").append(Affected.format(h, false)) }
        return capped(cx, notes, body)
    }

    fun affectedGit(
        cx: Ctx,
        scope: List<Member>,
        since: String?,
        staged: Boolean,
        json: Boolean,
    ) = affected(cx, scope, scope.map { Affected.gitChangedFiles(it.root, since, staged) }, 3, json)

    /** `affected` for one path (MCP `impact` without a name): the path belongs to the project that holds it. */
    fun affectedPath(
        cx: Ctx,
        scope: List<Member>,
        path: String,
        depth: Int,
    ): String {
        val changed = MutableList(scope.size) { emptyList<String>() }
        if (path.isNotEmpty()) {
            val at = holder(scope, path)
            changed[at] = listOf(Affected.relOf(scope[at].root, path))
        }
        return affected(cx, scope, changed, depth, false)
    }
}
