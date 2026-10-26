// Port of src/plugins/graph/rank.rs (T370): the SessionStart repo map ranked by file-level personalized PageRank.
// An edge A to B means file A references a name file B defines. The graph and its global ranks are stored as one
// `file_rank` document when an index run changes the root; a personalized rank is computed in memory, never stored.
package rtok.plugins.graph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.FileScanRow
import kotlin.math.abs
import kotlin.math.ln

/** The stored document: files by index, the global rank of each and the weighted edges, sorted by source file. */
@Serializable
data class FileGraph(
    val v: Int = 0,
    val paths: List<String> = emptyList(),
    /** Indexed mtime in nanoseconds, 0 when unknown. */
    val mtimes: List<Long> = emptyList(),
    /** Up to [Rank.TOP_DEFS] definition names per file, most referenced first. */
    val defs: List<List<String>> = emptyList(),
    val rank: List<Float> = emptyList(),
    val src: List<Int> = emptyList(),
    val dst: List<Int> = emptyList(),
    val weight: List<Float> = emptyList(),
) {
    /** PageRank by power iteration; [seeds] are where the walk restarts, none means every file equally. */
    fun pagerank(seeds: List<Int>): List<Double> = powerIteration(seeds, Rank.MAX_ITERATIONS, Rank.EPSILON)

    internal fun powerIteration(
        seeds: List<Int>,
        maxIterations: Int,
        epsilon: Double,
    ): List<Double> {
        val n = paths.size
        if (n == 0) return emptyList()
        val restart = DoubleArray(n)
        if (seeds.isEmpty()) restart.fill(1.0 / n) else for (s in seeds) restart[s] += 1.0 / seeds.size
        val outWeight = DoubleArray(n)
        for (i in src.indices) outWeight[src[i]] += weight[i].toDouble()
        var rank = restart.copyOf()
        repeat(maxIterations) {
            var dangling = 0.0
            for (i in 0 until n) if (outWeight[i] == 0.0) dangling += rank[i]
            val spread = Rank.DAMPING * dangling / n
            val next = DoubleArray(n) { (1.0 - Rank.DAMPING) * restart[it] + spread }
            for (i in src.indices) next[dst[i]] += Rank.DAMPING * rank[src[i]] * weight[i].toDouble() / outWeight[src[i]]
            var delta = 0.0
            for (i in 0 until n) delta += abs(next[i] - rank[i])
            rank = next
            if (delta < epsilon) return rank.toList()
        }
        return rank.toList()
    }

    /** Files edited within a day of [nowNanos], when that is a working set; empty otherwise. */
    internal fun recent(nowNanos: Long): List<Int> {
        val recent = paths.indices.filter { mtimes[it] > 0 && nowNanos - mtimes[it] < Rank.RECENT_NANOS }
        return if (recent.size > Rank.MAX_SEEDS) emptyList() else recent
    }

    internal fun indexOf(rel: String): Int? = paths.binarySearch(rel).takeIf { it >= 0 }

    /** Files by rank with their top definitions, filled in one pass against a running token estimate. */
    internal fun render(
        rank: List<Double>,
        cap: UInt,
        cx: Ctx,
    ): String? {
        val order =
            paths.indices
                .filter { defs[it].isNotEmpty() }
                .sortedWith { a, b -> rank[b].compareTo(rank[a]).takeIf { it != 0 } ?: paths[a].compareTo(paths[b]) }
        val text = StringBuilder("repo map\n")
        var used = cx.estimate(text.toString(), Class.Prose)
        var files = 0
        for (i in order) {
            val line = "${paths[i]}: ${defs[i].joinToString(", ")}\n"
            used += cx.estimate(line, Class.Prose)
            if (used > cap) break
            text.append(line)
            files++
        }
        return if (files > 0) text.toString().trimEnd() else null
    }
}

object Rank : FileRanks {
    internal const val DAMPING = 0.85
    internal const val MAX_ITERATIONS = 20

    /** L1 change between two iterations under which the vector counts as settled. */
    internal const val EPSILON = 1e-6
    internal const val TOP_DEFS = 2

    /** A file edited this recently (ns, as the index's mtime) is part of what the session is about. */
    internal const val RECENT_NANOS = 24L * 3600 * 1_000_000_000

    /** More recent files than this is a fresh clone or a checkout, not a working set. */
    internal const val MAX_SEEDS = 32

    /** Bumped when the document's shape changes; an older document reads as absent. */
    internal const val VERSION = 1

    /** T371: weight of a co-change pair against a symbol edge; history only breaks ties. */
    internal const val COCHANGE_WEIGHT = 0.3

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Build the graph from scan rows, the indexed files' mtimes and the co-change pairs. Deterministic. */
    fun build(
        scan: List<FileScanRow>,
        mtimes: Map<String, Long>,
        cochange: List<CochangePair>,
    ): FileGraph {
        val paths = (mtimes.keys + scan.map { it.path }).toSet().sorted()
        val ids = HashMap<String, Int>(paths.size * 2).apply { paths.forEachIndexed { i, p -> put(p, i) } }
        val n = paths.size
        val defFiles = HashMap<String, MutableList<Int>>()
        val refFiles = HashMap<String, MutableList<Pair<Int, Long>>>()
        val refTotal = HashMap<String, Long>()
        for (r in scan) {
            val id = ids.getValue(r.path)
            if (r.isDef) {
                defFiles.getOrPut(r.name) { mutableListOf() }.add(id)
            } else {
                refFiles.getOrPut(r.name) { mutableListOf() }.add(id to r.rows)
                refTotal[r.name] = (refTotal[r.name] ?: 0) + r.rows
            }
        }
        val edges = HashMap<Long, Double>()

        fun add(
            from: Int,
            to: Int,
            w: Double,
        ) {
            val k = (from.toLong() shl 32) or to.toLong()
            edges[k] = (edges[k] ?: 0.0) + w
        }
        // Names in sorted order, as the Rust BTreeMap walks them, so the float sums match.
        for (name in refFiles.keys.sorted()) {
            val referrers = refFiles.getValue(name)
            val definers = defFiles[name] ?: continue
            // A name referenced from most files says little about any one of them; the definers split the rest.
            val idf = ln(1.0 + n.toDouble() / referrers.size)
            for ((from, count) in referrers) for (to in definers) if (to != from) add(from, to, count * idf / definers.size)
        }
        // Both directions; a file the index does not know has no node to carry the edge.
        for (p in cochange) {
            val a = ids[p.a] ?: continue
            val b = ids[p.b] ?: continue
            val w = COCHANGE_WEIGHT * (1.0 + ln(p.count.toDouble()))
            add(a, b, w)
            add(b, a, w)
        }
        val defs = List(n) { mutableListOf<Pair<Long, String>>() }
        for (name in defFiles.keys.sorted()) {
            val refs = refTotal[name] ?: 0
            for (id in defFiles.getValue(name)) defs[id].add(-refs to name)
        }
        val keys = edges.keys.sorted()
        val g =
            FileGraph(
                v = VERSION,
                paths = paths,
                mtimes = paths.map { mtimes[it] ?: 0 },
                defs = defs.map { d -> d.sortedWith(compareBy<Pair<Long, String>> { it.first }.thenBy { it.second }).take(TOP_DEFS).map { it.second } },
                src = keys.map { (it ushr 32).toInt() },
                dst = keys.map { (it and 0xffffffffL).toInt() },
                weight = keys.map { edges.getValue(it).toFloat() },
            )
        return g.copy(rank = g.pagerank(emptyList()).map { it.toFloat() })
    }

    /** Rebuild and store the root's graph from the index; called after an index run that changed something. */
    override fun refresh(
        cx: Ctx,
        root: String,
    ) {
        val mtimes = cx.symbolStats(root).mapValues { it.value.second }
        val g = build(cx.symbolFileScan(root), mtimes, Cochange.pairs(cx, root))
        cx.fileRankPut(root, json.encodeToString(FileGraph.serializer(), g))
    }

    private fun stored(
        cx: Ctx,
        root: String,
    ): FileGraph? = runCatching { json.decodeFromString(FileGraph.serializer(), cx.fileRankGet(root)!!) }.getOrNull()?.takeIf { it.v == VERSION }

    /** T377: the stored global rank of each file; empty when nothing (or an older shape) is stored. */
    fun ranks(
        cx: Ctx,
        root: String,
    ): Map<String, Float> = stored(cx, root)?.let { g -> g.paths.zip(g.rank).toMap() } ?: emptyMap()

    /** Whether the root has no stored graph yet, as after an upgrade over a current index. */
    override fun missing(
        cx: Ctx,
        root: String,
    ): Boolean = runCatching { cx.fileRankGet(root) }.getOrNull() == null

    /** The SessionStart map of [root], personalized by recent edits and the last checkpoint's files; null without a graph. */
    fun map(
        cx: Ctx,
        root: String,
        cap: UInt,
        checkpointPaths: List<String>,
        nowNanos: Long,
    ): String? {
        val g = stored(cx, root) ?: return null
        val seeds = (g.recent(nowNanos) + checkpointPaths.mapNotNull(g::indexOf)).distinct().sorted()
        val rank = if (seeds.isEmpty()) g.rank.map { it.toDouble() } else g.pagerank(seeds)
        return g.render(rank, cap, cx)
    }
}
