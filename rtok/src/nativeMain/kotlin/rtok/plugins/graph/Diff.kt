// Port of src/plugins/graph/diff.rs (T329.18): what a change did to the graph. The old side is read from
// git's object database, parsed by the index's own extractor and kept in memory, so there is no checkout,
// no second store and nothing for the working tree to notice. The new side is the project's index (or a
// second revision read the same way). Only the files git reports as different are parsed and compared.
package rtok.plugins.graph

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.DefRow
import rtok.store.RefGroup
import rtok.store.symbolDefScan
import rtok.store.symbolRefScan

/** A call edge: `(path, scope, name)`. */
typealias DiffEdgeKey = Triple<String, String, String>

/** A registry link by project names, because an export's ids belong to another registry. */
typealias LinkKey = Triple<String, String, String>

data class DiffQuery(
    /** `REF` for every project, `PROJECT:REF` for one; none is `HEAD`. */
    val from: List<String> = emptyList(),
    /** null is the working tree. */
    val to: String? = null,
    val json: Boolean = false,
    /** A saved `symbols` export as the old side (CLI only: an MCP caller must not choose a path). */
    val export: String? = null,
)

class Changed(
    val old: DefRow,
    val new: DefRow,
)

class GraphDiff {
    var added: MutableList<DefRow> = ArrayList()
    var removed: MutableList<DefRow> = ArrayList()
    val changed: MutableList<Changed> = ArrayList()
    var moved: List<Pair<DefRow, DefRow>> = emptyList()
    var renamed: List<Pair<DefRow, DefRow>> = emptyList()
    var edgesAdded: List<DiffEdgeKey> = emptyList()
    var edgesRemoved: List<DiffEdgeKey> = emptyList()

    /** Files that differ but no grammar read, with why. */
    var notAnalysed: List<Pair<String, String>> = emptyList()

    fun isEmpty() =
        added.isEmpty() && removed.isEmpty() && changed.isEmpty() && moved.isEmpty() && renamed.isEmpty() &&
            edgesAdded.isEmpty() && edgesRemoved.isEmpty() && notAnalysed.isEmpty()

    /** T329.43: the files of the rows the text lists; quoted caller sites are context, not rows. */
    internal fun tally(root: String) {
        if (isEmpty()) return
        val paths =
            changed.map { it.new.path } + added.map { it.path } + removed.map { it.path } + renamed.map { it.second.path } +
                moved.flatMap { listOf(it.first.path, it.second.path) } + edgesAdded.map { it.first } + edgesRemoved.map { it.first } +
                notAnalysed.map { it.first }
        Tally.hit(root, "", paths)
    }

    /** Symbols whose callers matter to a reviewer: what changed, vanished or changed name. */
    internal fun touched(): List<String> {
        val all = changed.map { it.new.name } + removed.map { it.name } + renamed.map { it.first.name }
        val out = ArrayList<String>()
        for (n in all) if (out.lastOrNull() != n) out += n
        return out.take(GraphDiffs.CALLERS_SYMBOLS)
    }
}

/** Where a side reads a definition's text from when a rename has to be told from a delete and an add. */
internal sealed interface Src {
    /** A saved export: names and places only. */
    data object Names : Src

    class Disk(
        val root: String,
    ) : Src

    class Git(
        val root: String,
        val oids: Map<String, String>,
    ) : Src
}

/** One tree's definitions and call groups for the files being compared. */
internal class Side(
    val defs: List<DefRow>,
    var refs: List<RefGroup>,
    val src: Src,
    /** Files present on this side that were not UTF-8 or that the grammar failed on. */
    val unread: List<String> = emptyList(),
) {
    private val cache = HashMap<String, ByteArray?>()

    fun body(d: DefRow): String? {
        val bytes =
            cache.getOrPut(d.path) {
                when (val s = src) {
                    Src.Names -> null
                    is Src.Disk -> runCatching { rtok.sys.readBytes("${s.root}/${d.path}") }.getOrNull()
                    is Src.Git -> s.oids[d.path]?.let { Git.stdout(s.root, listOf("cat-file", "blob", it)) }
                }
            } ?: return null
        val (start, end) = d.startByte to d.endByte
        if (start < 0 || end < start || end > bytes.size) return null
        return bytes.copyOfRange(start.toInt(), end.toInt()).decodeToString()
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DiffReport(
    val from: String,
    val to: String,
    val projects: List<DiffProject>,
    @SerialName("links_added") val linksAdded: List<DiffLink>,
    @SerialName("links_removed") val linksRemoved: List<DiffLink>,
    /** Linked projects that could not answer, and what an export cannot compare. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val notes: String = "",
) {
    /** At most [n] rows per list and project, the rest counted in `more`. */
    fun capped(n: Int): DiffReport =
        copy(
            projects =
                projects.map { p ->
                    fun <T> cut(v: List<T>) = v.take(n) to (v.size - n).coerceAtLeast(0)
                    val c = cut(p.changed)
                    val a = cut(p.added)
                    val r = cut(p.removed)
                    val rn = cut(p.renamed)
                    val mv = cut(p.moved)
                    val ea = cut(p.edgesAdded)
                    val er = cut(p.edgesRemoved)
                    val na = cut(p.notAnalysed)
                    p.copy(
                        changed = c.first,
                        added = a.first,
                        removed = r.first,
                        renamed = rn.first,
                        moved = mv.first,
                        edgesAdded = ea.first,
                        edgesRemoved = er.first,
                        notAnalysed = na.first,
                        more = c.second + a.second + r.second + rn.second + mv.second + ea.second + er.second + na.second,
                    )
                },
        )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DiffProject(
    val project: String,
    val from: String,
    val changed: List<DiffDef>,
    val added: List<DiffDef>,
    val removed: List<DiffDef>,
    val renamed: List<DiffMove>,
    val moved: List<DiffMove>,
    @SerialName("edges_added") val edgesAdded: List<DiffEdge>,
    @SerialName("edges_removed") val edgesRemoved: List<DiffEdge>,
    @SerialName("not_analysed") val notAnalysed: List<DiffUnread>,
    /** Rows left out of the lists by [DiffReport.capped]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val more: Int = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DiffDef(
    val name: String,
    val kind: String,
    val path: String,
    val line: Int,
    /** `changed` rows only: the signature moved, not just the body. */
    @SerialName("signature_changed") @EncodeDefault(EncodeDefault.Mode.NEVER) val signatureChanged: Boolean? = null,
    /** `changed` and `removed` rows only. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val callers: List<String>? = null,
)

@Serializable data class DiffMove(
    val from: DiffDef,
    val to: DiffDef,
)

@Serializable data class DiffEdge(
    val path: String,
    val scope: String,
    val name: String,
)

@Serializable data class DiffLink(
    val from: String,
    val to: String,
    val kind: String,
)

@Serializable data class DiffUnread(
    val path: String,
    val reason: String,
)

/** What the graph page's Compare mode asks; the old side is never a path, only an export's text. */
@Serializable data class DiffRequest(
    /** An id or a root path, as in `rtok graph projects`. */
    val project: String,
    val from: List<String> = emptyList(),
    val to: String? = null,
    val export: DiffExport? = null,
)

@Serializable data class DiffExport(
    val name: String,
    val text: String,
)

/** A saved export as the old side, with the name the answer calls it by. */
class Saved(
    val export: Export,
    val label: String,
) {
    companion object {
        fun read(path: String) = Saved(ExportView.read(path), path)
    }
}

object GraphDiffs {
    /** Callers shown under one symbol, and the symbols whose callers are looked up at all. */
    const val CALLERS_SHOWN = 5
    const val CALLERS_SYMBOLS = 60
    const val CALLER_DEPTH = 2

    /** Past this many unmatched definitions on a side a rename cannot be told from noise. */
    const val RENAME_CANDIDATES = 400

    /** Rows per list and project in one page frame. */
    const val PAGE_ROWS = 500

    /** Kinds a test gap is scored for (review.rs `SCORED_KINDS`). */
    val SCORED_KINDS = listOf("function", "method", "class")

    private val json = Json { encodeDefaults = true }

    private val edgeOrder = compareBy<Triple<String, String, String>>({ it.first }, { it.second }, { it.third })

    private fun git(
        root: String,
        args: List<String>,
    ): ByteArray =
        Git.stdoutResult(root, args).getOrElse { e ->
            throw IllegalStateException((e.message ?: "$e").replaceFirst("git diff failed", "git failed"))
        }

    /** The commit [rev] names. A leading `-` would be read as an option, so it is never a ref. */
    internal fun resolveRev(
        root: String,
        rev: String,
    ): String {
        if (Git.stdout(root, listOf("rev-parse", "--is-inside-work-tree")) == null) throw IllegalStateException("$root is not a git repository")
        val oid =
            if (rev.isEmpty() || rev.startsWith("-")) {
                null
            } else {
                Git
                    .stdout(root, listOf("rev-parse", "--verify", "--quiet", "$rev^{commit}"))
                    ?.decodeToString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        return oid ?: throw IllegalStateException("unknown ref `$rev` in $root")
    }

    /** `path -> blob id` of every file in [rev], relative to [root]. */
    internal fun treeOids(
        root: String,
        rev: String,
    ): Map<String, String> {
        val out = HashMap<String, String>()
        for (entry in git(root, listOf("ls-tree", "-r", "-z", rev)).decodeToString().split('\u0000')) {
            val tab = entry.indexOf('\t').takeIf { it >= 0 } ?: continue
            val meta = entry.substring(0, tab).split(' ')
            if (meta.size >= 3 && meta[1] == "blob") out[entry.substring(tab + 1)] = meta[2]
        }
        return out
    }

    /** Files whose bytes differ between [from] and [to] (the working tree when null), sorted. */
    internal fun changedPaths(
        root: String,
        from: String,
        to: String?,
    ): MutableSet<String> {
        val args = listOf("diff", "--name-only", "-z", "--no-renames", "--relative", from) + listOfNotNull(to)
        return git(root, args)
            .decodeToString()
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .toSortedSet()
    }

    private fun <T : Comparable<T>> Iterable<T>.toSortedSet(): MutableSet<T> = LinkedHashSet(sorted())

    /** Reads each `(path, blob)` with one `git cat-file --batch`; the requests go in through a file. */
    private fun eachBlob(
        root: String,
        entries: List<Pair<String, String>>,
        f: (String, ByteArray) -> Unit,
    ) {
        if (entries.isEmpty()) return
        val req = "${rtok.sys.tempDir()}/rtok-diff-${rtok.sys.pid()}-${entries.hashCode()}.txt"
        rtok.sys.writeString(req, entries.joinToString("") { it.second + "\n" })
        val out =
            try {
                val (c, dropped) =
                    rtok.sys
                        .ProgressRunner("", "git", listOf("-C", root, "cat-file", "--batch"))
                        .quiet()
                        .stdinFile(req)
                        .runCapped(1L shl 31)
                if (c.exitCode != 0 || dropped > 0) throw IllegalStateException("git failed: cat-file --batch")
                c.stdout
            } finally {
                runCatching { rtok.sys.removeFile(req) }
            }
        var at = 0
        for ((path, _) in entries) {
            val nl = (at until out.size).firstOrNull { out[it] == '\n'.code.toByte() } ?: throw IllegalStateException("git cat-file: unexpected reply for $path")
            val size =
                out
                    .copyOfRange(at, nl)
                    .decodeToString()
                    .split(' ')
                    .getOrNull(2)
                    ?.toIntOrNull() ?: throw IllegalStateException("git cat-file: unexpected reply for $path")
            f(path, out.copyOfRange(nl + 1, nl + 1 + size))
            at = nl + 1 + size + 1
        }
    }

    /** The files [paths] names in a revision's [tree], extracted as the index extracts a file on disk. */
    internal fun gitSide(
        matcher: Matcher,
        root: String,
        tree: Map<String, String>,
        paths: Set<String>,
    ): Side {
        val entries = paths.filter { matcher.indexable("$root/$it") }.mapNotNull { p -> tree[p]?.let { p to it } }
        val defs = ArrayList<DefRow>()
        val groups = HashMap<List<String>, Long>()
        val unread = ArrayList<String>()
        eachBlob(root, entries) { path, bytes ->
            val src = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            val rows = src?.let { Index.rowsOf("$root/$path", it, matcher.extensions) }
            if (rows == null) {
                unread += path
                return@eachBlob
            }
            for (r in rows) {
                if (r.name.isEmpty()) continue
                if (r.isDef) {
                    defs += DefRow(path, r.name, r.kind, r.line, r.endLine, r.signature, r.contentHash, r.startByte, r.endByte)
                } else if (r.kind != "import") {
                    val k = listOf(path, r.scope, r.name, r.kind)
                    groups[k] = (groups[k] ?: 0L) + 1
                }
            }
        }
        val refs =
            groups.entries
                .sortedWith(compareBy({ it.key[0] }, { it.key[1] }, { it.key[2] }, { it.key[3] }))
                .map { (k, n) -> RefGroup(k[0], k[1], k[2], k[3], n) }
        return Side(defs, refs, Src.Git(root, entries.toMap()), unread)
    }

    /** Definitions grouped by `(path, name, kind)` in key order, each group in source order. */
    private fun group(defs: List<DefRow>): Map<Triple<String, String, String>, List<Int>> = defs.indices.groupBy { Triple(defs[it].path, defs[it].name, defs[it].kind) }.mapValues { (_, v) -> v.sortedBy { defs[it].line } }

    /** Definitions that hold other definitions (a module, a class): only their own declaration counts. */
    private fun containers(defs: List<DefRow>): BooleanArray {
        val order = defs.indices.sortedWith(compareBy({ defs[it].path }, { defs[it].startByte }, { -defs[it].endByte }))
        val out = BooleanArray(defs.size)
        for (w in order.windowed(2)) {
            val (a, b) = defs[w[0]] to defs[w[1]]
            out[w[0]] = a.path == b.path && b.startByte < a.endByte
        }
        return out
    }

    /** Pairs one removed with one added definition that share a key; a shared key pairs none. */
    private fun <K> uniquePairs(
        removed: MutableList<DefRow>,
        added: MutableList<DefRow>,
        key: (Boolean, DefRow) -> K?,
    ): List<Pair<DefRow, DefRow>> {
        val by = LinkedHashMap<K, Pair<MutableList<Int>, MutableList<Int>>>()
        removed.forEachIndexed { i, d -> key(false, d)?.let { by.getOrPut(it) { ArrayList<Int>() to ArrayList() }.first += i } }
        added.forEachIndexed { i, d -> key(true, d)?.let { by.getOrPut(it) { ArrayList<Int>() to ArrayList() }.second += i } }
        val pairs = by.values.filter { (r, a) -> r.size == 1 && a.size == 1 }.map { (r, a) -> r[0] to a[0] }
        val gone = pairs.map { it.first }.toSet()
        val new = pairs.map { it.second }.toSet()
        val out = pairs.map { (r, a) -> removed[r] to added[a] }.sortedWith(compareBy({ it.first.path }, { it.first.line }))
        val keptRemoved = removed.filterIndexed { i, _ -> i !in gone }
        val keptAdded = added.filterIndexed { i, _ -> i !in new }
        removed.clear()
        removed += keptRemoved
        added.clear()
        added += keptAdded
        return out
    }

    internal fun diffSides(
        old: Side,
        new: Side,
    ): GraphDiff {
        val d = GraphDiff()
        val oldBox = containers(old.defs)
        val newBox = containers(new.defs)
        val go = group(old.defs)
        val gn = group(new.defs)
        // A saved export has no signatures or hashes, so it can say what appeared and vanished only.
        val blind = old.src == Src.Names
        val keys = (go.keys + gn.keys).sortedWith(edgeOrder)
        for (k in keys) {
            val o = go[k].orEmpty()
            val n = gn[k].orEmpty()
            for (i in 0 until maxOf(o.size, n.size)) {
                val a = o.getOrNull(i)
                val b = n.getOrNull(i)
                when {
                    a != null && b != null -> {
                        if (blind) continue
                        val (ad, bd) = old.defs[a] to new.defs[b]
                        val hashes = ad.contentHash.isNotEmpty() && bd.contentHash.isNotEmpty()
                        val ownBody = !oldBox[a] && !newBox[b] && hashes
                        if (ad.signature != bd.signature || (ownBody && ad.contentHash != bd.contentHash)) d.changed += Changed(ad, bd)
                    }

                    a != null -> {
                        d.removed += old.defs[a]
                    }

                    b != null -> {
                        d.added += new.defs[b]
                    }
                }
            }
        }
        d.moved = uniquePairs(d.removed, d.added) { _, def -> if (def.contentHash.isEmpty()) null else Triple(def.name, def.kind, def.contentHash) }
        if (d.removed.size <= RENAME_CANDIDATES && d.added.size <= RENAME_CANDIDATES) {
            // The name sits inside the span, so a rename changes the hash; masking it compares the rest.
            d.renamed =
                uniquePairs(d.removed, d.added) { isNew, def ->
                    val text = (if (isNew) new else old).body(def) ?: return@uniquePairs null
                    def.kind to text.replace(def.name, "\u0000")
                }
        }
        // A renamed or moved caller keeps its calls; without this each of them shows as an edge pair.
        val follow = (d.renamed + d.moved).associate { (a, b) -> (a.path to a.name) to (b.path to b.name) }
        val before =
            old.refs
                .map { r ->
                    follow[r.path to r.scope]?.let { (p, s) -> Triple(p, s, r.name) } ?: Triple(r.path, r.scope, r.name)
                }.toSet()
        val after = new.refs.map { Triple(it.path, it.scope, it.name) }.toSet()
        d.edgesAdded = (after - before).sortedWith(edgeOrder)
        d.edgesRemoved = (before - after).sortedWith(edgeOrder)
        return d
    }

    /** The project's index as the new side, cut to [paths]; indexed files git does not know at [tree] join [paths]. */
    private fun workingSide(
        rt: Runtime,
        root: String,
        tree: Map<String, String>,
        paths: MutableSet<String>,
    ): Side {
        val key = Index.canon(root)
        val defs = rt.store.symbolDefScan(key)
        val refs = rt.store.symbolRefScan(key)
        val extra = (defs.map { it.path } + refs.map { it.path }).filter { it !in tree }
        if (extra.isNotEmpty()) {
            val all = (paths + extra).sorted()
            paths.clear()
            paths += all
        }
        return Side(defs.filter { it.path in paths }, refs.filter { it.path in paths }, Src.Disk(root))
    }

    /** One project's diff between revisions. */
    internal fun compute(
        rt: Runtime,
        cx: Ctx,
        root: String,
        rev: String,
        toRev: String?,
    ): GraphDiff {
        val from = resolveRev(root, rev)
        val to = toRev?.let { resolveRev(root, it) }
        GraphCore.indexFor(cx, root)
        val matcher = Matcher(root, graphCfg(cx))
        val oldTree = treeOids(root, from)
        val paths = changedPaths(root, from, to)
        // Taken before untracked files join `paths`: only what git reports as different can be unread.
        val listed = paths.toList()
        val new = if (to == null) workingSide(rt, root, oldTree, paths) else gitSide(matcher, root, treeOids(root, to), paths)
        val old = gitSide(matcher, root, oldTree, paths)
        // The working side is the index, which keeps no record of a file it could not read.
        val unreadableNow =
            if (to != null) {
                emptyList()
            } else {
                listed.filter { p ->
                    val b = runCatching { rtok.sys.readBytes("$root/$p") }.getOrNull()
                    b != null && runCatching { b.decodeToString(throwOnInvalidSequence = true) }.isFailure
                }
            }
        val unread = (old.unread + new.unread + unreadableNow).toSet()
        val notAnalysed =
            listed.filter { !matcher.isExcluded("$root/$it") }.mapNotNull { p ->
                when {
                    !matcher.hasSupportedExt(p) -> p to "no grammar"
                    p in unread -> p to "not parsed"
                    else -> null
                }
            }
        return diffSides(old, new).also { it.notAnalysed = notAnalysed }
    }

    /** One project against a saved export: what appeared and vanished by `(path, name, kind)` only. */
    private fun computeSaved(
        rt: Runtime,
        cx: Ctx,
        m: Member,
        e: Export,
    ): GraphDiff {
        if (e.meta.level == "overview") return GraphDiff()
        val ids =
            e.projects
                .filter { it.name == m.name }
                .map { it.id }
                .toSet()
        if (ids.isEmpty()) throw IllegalStateException("${m.name} is not in the export")
        GraphCore.indexFor(cx, m.root)
        val defs = e.nodes.filter { it.project in ids }.map { DefRow(it.path, it.name, it.kind, it.line, it.line, "", "", 0, 0) }
        val new = workingSide(rt, m.root, emptyMap(), LinkedHashSet())
        new.refs = emptyList()
        return diffSides(Side(defs, emptyList(), Src.Names), new)
    }

    /** Registry links of [old] the current registry lacks and the other way round, over projects both name. */
    private fun linksBetween(
        rt: Runtime,
        scope: List<Member>,
        old: Export,
    ): Pair<List<LinkKey>, List<LinkKey>> {
        val now = ExportView.collect(rt, scope, ExportQuery(ExportLevel.Overview, null, 2, redact = false, pretty = false))

        fun keys(e: Export): Set<LinkKey> {
            fun name(id: Int) = e.projects.firstOrNull { it.id == id }?.name
            return e.links.mapNotNull { l -> Triple(name(l.from) ?: return@mapNotNull null, name(l.to) ?: return@mapNotNull null, l.kind) }.toSet()
        }
        val before = keys(old)
        val after = keys(now)
        val both =
            old.projects
                .map { it.name }
                .filter { n -> now.projects.any { it.name == n } }
                .toSet()

        fun known(l: LinkKey) = l.first in both && l.second in both
        return after.filter { it !in before && known(it) }.sortedWith(edgeOrder) to before.filter { it !in after && known(it) }.sortedWith(edgeOrder)
    }

    /** `who` is the member's name, or an id or directory `rtok graph projects` knows for its root. */
    private fun answers(
        rt: Runtime,
        who: String,
        m: Member,
    ): Boolean = who == m.name || runCatching { Index.canon(Projects.resolve(rt.store, who).root) == Index.canon(m.root) }.getOrDefault(false)

    /** The revision each project is compared from: its own `PROJECT:REF`, else the shared `REF`. */
    private class Refs(
        val shared: String,
        val own: List<Pair<String, String>>,
    ) {
        fun of(
            rt: Runtime,
            m: Member,
        ): String = own.lastOrNull { answers(rt, it.first, m) }?.second ?: shared
    }

    private fun parseRefs(
        rt: Runtime,
        scope: List<Member>,
        specs: List<String>,
    ): Refs {
        var shared: String? = null
        val own = ArrayList<Pair<String, String>>()
        for (spec in specs) {
            // A name no project answers to leaves the whole text a ref (`@{yesterday 10:00}` still works).
            val at = spec.lastIndexOf(':')
            val who = if (at >= 0) spec.substring(0, at) else null
            when {
                who != null && scope.any { answers(rt, who, it) } -> own += who to spec.substring(at + 1)
                shared != null -> throw IllegalStateException("--from names a ref for the other projects twice")
                else -> shared = spec
            }
        }
        return Refs(shared ?: "HEAD", own)
    }

    /** Callers of [name] in the current indexes of the whole scope; each is `[project] path scope dN`. */
    private fun callers(
        cx: Ctx,
        keys: List<String>,
        labels: List<String>,
        name: String,
    ): List<String> =
        Impact
            .walkRoots(cx, keys, name, CALLER_DEPTH, true)
            .sortedWith(compareBy({ it.depth }, { it.root }, { it.path }, { it.scope }))
            .map { r -> "${labels[r.root]}${r.path}${if (r.scope.isEmpty()) "" else " ${r.scope}"} d${r.depth}" }

    private class Part(
        val name: String,
        /** The revision this project was compared from. */
        val rev: String,
        val diff: GraphDiff,
        val callers: Map<String, List<String>>,
    ) {
        fun callersOf(name: String) = callers[name].orEmpty()
    }

    private fun defText(d: DefRow) = "${d.kind} ${d.name}  ${d.path}:${d.line}"

    private fun <T> StringBuilder.listed(
        title: String,
        rows: List<T>,
        line: (T) -> String,
    ) {
        if (rows.isEmpty()) return
        append("$title (${rows.size})\n")
        for (r in rows) append("  ${line(r)}\n")
    }

    private fun edgeText(e: DiffEdgeKey) = if (e.second.isEmpty()) "${e.first} -> ${e.third}" else "${e.first} ${e.second} -> ${e.third}"

    private fun renderPart(
        out: StringBuilder,
        p: Part,
        tag: String,
    ) {
        val d = p.diff
        out.append(
            "$tag${d.changed.size} changed · ${d.added.size} added · ${d.removed.size} removed · ${d.renamed.size} renamed · " +
                "${d.moved.size} moved · edges +${d.edgesAdded.size} -${d.edgesRemoved.size}\n",
        )
        if (d.notAnalysed.isNotEmpty()) out.append("$tag${d.notAnalysed.size} changed, not analysed\n")
        val changed = d.changed.sortedByDescending { p.callersOf(it.new.name).size }
        if (changed.isNotEmpty()) out.append("changed (${changed.size})\n")
        for (c in changed) {
            val what = if (c.old.signature != c.new.signature) "signature" else "body"
            val list = p.callersOf(c.new.name)
            out.append("  ${defText(c.new)}  [$what] ${list.size} callers\n")
            for (l in list.take(CALLERS_SHOWN)) out.append("    $l\n")
        }
        val untested = changed.filter { untested(it.new, p.callersOf(it.new.name)) }.map { it.new.name }
        if (untested.isNotEmpty()) out.append("untested: ${untested.joinToString(", ")}\n")
        out.listed("removed", d.removed) { "${defText(it)}  ${p.callersOf(it.name).size} callers" }
        out.listed("renamed", d.renamed) { (a, b) -> "${a.name} -> ${b.name}  ${b.path}:${b.line}" }
        out.listed("moved", d.moved) { (a, b) -> "${a.kind} ${a.name}  ${a.path} -> ${b.path}" }
        out.listed("added", d.added, ::defText)
        out.listed("edges added", d.edgesAdded, ::edgeText)
        out.listed("edges removed", d.edgesRemoved, ::edgeText)
        out.listed("changed, not analysed", d.notAnalysed) { (path, why) -> "$path  ($why)" }
    }

    /** T664: a changed function, method or class no test reaches within [CALLER_DEPTH] callers. */
    internal fun untested(
        d: DefRow,
        callers: List<String>,
    ) = d.kind in SCORED_KINDS && !Dead.isTestPath(d.path) && callers.none { Dead.isTestPath(callerPath(it)) }

    /** The path in a `[project] path scope dN` caller row. */
    internal fun callerPath(row: String): String {
        val r = if (row.startsWith("[")) row.substring(1).let { s -> s.indexOf("] ").takeIf { it >= 0 }?.let { s.substring(it + 2) } ?: s } else row
        return r.split(' ', '\t').firstOrNull { it.isNotEmpty() } ?: ""
    }

    private fun defRow(d: DefRow) = DiffDef(d.name, d.kind, d.path, d.line)

    private fun moveRow(m: Pair<DefRow, DefRow>) = DiffMove(defRow(m.first), defRow(m.second))

    private fun edgeRow(e: DiffEdgeKey) = DiffEdge(e.first, e.second, e.third)

    private fun linkRow(l: LinkKey) = DiffLink(l.first, l.second, l.third)

    private fun projectReport(p: Part): DiffProject {
        val d = p.diff
        return DiffProject(
            p.name,
            p.rev,
            d.changed.map { defRow(it.new).copy(signatureChanged = it.old.signature != it.new.signature, callers = p.callersOf(it.new.name)) },
            d.added.map(::defRow),
            d.removed.map { defRow(it).copy(callers = p.callersOf(it.name)) },
            d.renamed.map(::moveRow),
            d.moved.map(::moveRow),
            d.edgesAdded.map(::edgeRow),
            d.edgesRemoved.map(::edgeRow),
            d.notAnalysed.map { DiffUnread(it.first, it.second) },
        )
    }

    private class Gathered(
        val parts: List<Part>,
        val labels: List<String>,
        val links: Pair<List<LinkKey>, List<LinkKey>>,
        val notes: String,
        val from: String,
        val to: String,
        val shared: String,
        val saved: Boolean,
    )

    private fun gather(
        rt: Runtime,
        scope: List<Member>,
        from: List<String>,
        to: String?,
        saved: Saved?,
    ): Gathered {
        val cx = Ctx(rt)
        if (saved != null) {
            if (to != null || from.isNotEmpty()) throw IllegalStateException("a saved export is compared with the working tree; drop --from and --to")
            if (saved.export.meta.level == "focus") throw IllegalStateException("${saved.label} holds a part of the graph; export with --level symbols to compare")
        }
        val refs = parseRefs(rt, scope, from)
        val (done, skipped) =
            Scope.fanOut(scope) { m ->
                Scope.walkable(m)
                if (saved != null) computeSaved(rt, cx, m, saved.export) else compute(rt, cx, m.root, refs.of(rt, m), to)
            }
        val notes = StringBuilder(skipped)
        val links = saved?.let { linksBetween(rt, scope, it.export) } ?: (emptyList<LinkKey>() to emptyList())
        if (saved != null) {
            notes.append(
                if (saved.export.meta.level == "overview") {
                    "note: the export has no symbols (made with --level overview); only links are compared\n"
                } else {
                    "note: against an export only added and removed symbols are listed; changes and edges need a revision\n"
                },
            )
        }
        val labels = done.map { (m, _) -> if (scope.size > 1) "[${m.name}] " else "" }
        val keys = done.map { (m, _) -> Index.canon(m.root) }
        val parts =
            done.map { (m, diff) ->
                diff.tally(m.root)
                val found = LinkedHashMap<String, List<String>>()
                for (name in diff.touched()) if (name !in found) found[name] = callers(cx, keys, labels, name)
                Part(m.name, refs.of(rt, m), diff, found)
            }
        return Gathered(parts, labels, links, notes.toString(), saved?.let { "export ${it.label}" } ?: refs.shared, to ?: "working", refs.shared, saved != null)
    }

    /** The diff of [scope] as the page and `--json` carry it: whole, with callers of changed and removed symbols. */
    fun report(
        rt: Runtime,
        scope: List<Member>,
        from: List<String>,
        to: String?,
        saved: Saved?,
    ): DiffReport {
        val g = gather(rt, scope, from, to, saved)
        return DiffReport(g.from, g.to, g.parts.map(::projectReport), g.links.first.map(::linkRow), g.links.second.map(::linkRow), g.notes)
    }

    /** The diff of every project of [scope]; the text goes through the graph cap, `--json` is whole. */
    fun run(
        rt: Runtime,
        scope: List<Member>,
        q: DiffQuery,
    ): String {
        val saved = q.export?.let(Saved::read)
        if (q.json) return json.encodeToString(DiffReport.serializer(), report(rt, scope, q.from, q.to, saved)) + "\n"
        val cx = Ctx(rt)
        val g = gather(rt, scope, q.from, q.to, saved)
        val body = StringBuilder("graph diff ${g.from} -> ${g.to} (tags)\n")
        if (g.parts.all { it.diff.isEmpty() } && g.links.first.isEmpty() && g.links.second.isEmpty()) body.append("no graph changes\n")
        for ((p, label) in g.parts.zip(g.labels)) {
            if (p.diff.isEmpty()) continue
            // A project compared from its own ref says so; the shared one is in the title.
            val tag = if (!g.saved && p.rev != g.shared) "[${p.name} from ${p.rev}] " else label
            renderPart(body, p, tag)
        }
        body.listed("links added", g.links.first) { "${it.first} -> ${it.second} (${it.third})" }
        body.listed("links removed", g.links.second) { "${it.first} -> ${it.second} (${it.third})" }
        return Tools.cap(cx, g.notes + body)
    }

    /** The page's answer: the same report `--json` prints, capped. */
    fun page(
        rt: Runtime,
        req: DiffRequest,
    ): DiffReport {
        val saved = req.export?.let { Saved(ExportView.parse(it.text, it.name), it.name) }
        val to = req.to?.takeIf { it.isNotEmpty() && it != "working" }
        return pageOf(rt, req.project, req.from, to, saved)
    }

    /** [page] once the old side is known, so the TUI's compare view resolves and caps exactly as the page does. */
    fun pageOf(
        rt: Runtime,
        project: String,
        from: List<String>,
        to: String?,
        saved: Saved?,
    ): DiffReport {
        if (project.isEmpty()) throw IllegalStateException("diff needs a project")
        val scope = Scope.resolve(rt.store, project, ".")
        return report(rt, scope, from, to, saved).capped(PAGE_ROWS)
    }

    /** MCP `graph_diff`: `from` (a ref, or a list mixing refs and `project:ref`; default `HEAD`), `to` (default the working tree). */
    fun call(
        rt: Runtime,
        args: JsonObject,
        scope: List<Member>,
    ): String {
        val to = Outline.str(args["to"]).takeIf { it.isNotEmpty() && it != "working" }
        val from =
            when (val f = args["from"]) {
                is JsonArray -> f.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                else -> listOfNotNull(Outline.str(f).takeIf { it.isNotEmpty() })
            }
        return run(rt, scope, DiffQuery(from, to))
    }
}
