package rtok.plugins.graph

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.*
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.FileScanRow
import rtok.plugin.sdk.SymbolRow
import rtok.support.withRuntime

class RankTest {
    /** Long after the fixtures' mtime of 1 ns, so no file counts as recently edited. */
    private val now = 100 * Rank.RECENT_NANOS

    private fun graph(n: Int, edges: List<Pair<Int, Int>>) = FileGraph(
        v = Rank.VERSION, paths = List(n) { "f$it.rs" }, mtimes = List(n) { 0L }, defs = List(n) { listOf("d") },
        src = edges.map { it.first }, dst = edges.map { it.second }, weight = edges.map { 1f },
    )
    private fun row(name: String, path: String, isDef: Boolean, n: Long) = FileScanRow(name, path, isDef, n)
    private fun edges(g: FileGraph) = g.src.zip(g.dst)

    /** Three leaves and a hub pointing at each other have a closed-form stationary vector. */
    @Test fun fourNodeGraphConvergesToTheKnownVector() {
        val g = graph(4, listOf(1 to 0, 2 to 0, 3 to 0, 0 to 1, 0 to 2, 0 to 3))
        val d = Rank.DAMPING; val hub = (1 + 3 * d) / (4 * (1 + d)); val leaf = (1 - hub) / 3
        val exact = g.powerIteration(emptyList(), 1000, 1e-15)
        assertTrue(abs(exact.sum() - 1) < 1e-9)
        assertTrue(abs(exact[0] - hub) < 1e-9, "$exact")
        assertTrue(exact.drop(1).all { abs(it - leaf) < 1e-9 }, "$exact")
        val shipped = g.pagerank(emptyList())
        assertTrue(abs(shipped.sum() - 1) < 1e-9)
        assertTrue(abs(shipped[0] - hub) < 0.02, "$shipped")
    }

    @Test fun danglingMassIsSpreadAndAnEmptyGraphIsEmpty() {
        assertTrue(graph(3, emptyList()).pagerank(emptyList()).all { abs(it - 1.0 / 3) < 1e-12 })
        assertTrue(graph(0, emptyList()).pagerank(emptyList()).isEmpty())
    }

    @Test fun seedsPullRankTowardThemselvesAndKeepTheMass() {
        val g = graph(4, listOf(0 to 1, 1 to 2, 3 to 2))
        val global = g.pagerank(emptyList()); val seeded = g.pagerank(listOf(3))
        assertTrue(abs(seeded.sum() - 1) < 1e-9)
        assertTrue(seeded[3] > global[3] * 1.5, "$global $seeded")
        assertTrue(seeded[0] < global[0], "$global $seeded")
        assertEquals(seeded, g.pagerank(listOf(3)), "same seeds, same bytes")
    }

    @Test fun rankFlowsToTheDefiningFileAndCommonNamesWeighLess() {
        val mtimes = listOf("a.rs", "b.rs", "c.rs", "d.rs").associateWith { 0L }
        val scan = listOf(
            row("core", "d.rs", true, 1), row("core", "a.rs", false, 1), row("core", "b.rs", false, 1), row("core", "c.rs", false, 1),
            row("local", "b.rs", true, 1), row("local", "a.rs", false, 4),
            row("external", "a.rs", false, 9), row("own", "c.rs", true, 1), row("own", "c.rs", false, 3),
        )
        val g = Rank.build(scan, mtimes, emptyList())
        assertEquals(listOf("a.rs", "b.rs", "c.rs", "d.rs"), g.paths)
        assertEquals(listOf(0 to 1, 0 to 3, 1 to 3, 2 to 3), edges(g))
        assertTrue(g.weight[0] > g.weight[1], "${g.weight}")
        assertTrue(abs(g.rank.sumOf { it.toDouble() } - 1) < 1e-6)
        assertTrue(g.rank[3] > g.rank[1] && g.rank[1] > g.rank[0], "${g.rank}")
        assertEquals(listOf("core"), g.defs[3])
        assertEquals(g, Rank.build(scan, mtimes, emptyList()), "deterministic")
    }

    @Test fun cochangePairsAddEdgesBetweenFilesThatShareNoSymbol() {
        val mtimes = listOf("a.rs", "b.rs", "c.rs").associateWith { 0L }
        val g = Rank.build(emptyList(), mtimes, listOf(CochangePair("a.rs", "c.rs", 4), CochangePair("a.rs", "gone.rs", 9)))
        assertEquals(listOf(0 to 2, 2 to 0), edges(g))
        val w = (Rank.COCHANGE_WEIGHT * (1 + ln(4.0))).toFloat()
        assertTrue(g.weight.all { abs(it - w) < 1e-6 }, "${g.weight}")
        assertTrue(g.rank[0] > g.rank[1] && g.rank[2] > g.rank[1], "${g.rank}")
    }

    @Test fun aNameInEveryFileIsDownWeightedBelowARareOne() {
        val paths = listOf("a.rs", "b.rs", "c.rs", "d.rs")
        val scan = listOf(row("common", "a.rs", true, 1), row("rare", "b.rs", true, 1)) +
            paths.map { row("common", it, false, 1) } + row("rare", "c.rs", false, 1)
        val g = Rank.build(scan, paths.associateWith { 0L }, emptyList())
        fun weight(from: Int, to: Int) = g.weight[edges(g).indexOf(from to to).also { check(it >= 0) }]
        assertTrue(weight(2, 1) > weight(2, 0), "${g.weight}")
    }

    @Test fun recentFilesSeedOnlyAWorkingSet() {
        val day = Rank.RECENT_NANOS; val now = 100 * day
        val base = graph(40, emptyList())
        val g = base.copy(mtimes = base.mtimes.toMutableList().also { it[5] = now - day / 2; it[7] = now - 2 * day })
        assertEquals(listOf(5), g.recent(now))
        assertTrue(base.copy(mtimes = List(40) { now - 1 }).recent(now).isEmpty(), "a fresh checkout seeds nothing")
        assertTrue(graph(3, emptyList()).recent(now).isEmpty(), "mtime 0 is unknown")
    }

    private fun seedRows(rt: Runtime, root: String) {
        fun def(n: String, l: Int) = SymbolRow(n, "function", l, true, l, "")
        fun use(n: String, l: Int) = SymbolRow(n, "function", l, false, l, "")
        fun put(path: String, rows: List<SymbolRow>) { rt.replaceSymbols(root, path, "s", 1L to 1L, rows) }
        put("core.rs", listOf(def("core_fn", 1), def("core_other", 2)))
        put("util.rs", listOf(def("util_fn", 1), use("core_fn", 5)))
        put("app.rs", listOf(def("app_main", 1), use("core_fn", 3), use("util_fn", 4)))
        put("ext.rs", listOf(def("ext_fn", 1)))
        put("side.rs", listOf(def("side_fn", 1), use("app_main", 2), use("ext_fn", 3)))
    }
    private fun listed(text: String) = text.lines().drop(1).map { it.substringBefore(':') }

    @Test fun theStoredGraphNeverHoldsSessionState() = withRuntime("rank-store") { rt ->
        val root = "/rank/store"; seedRows(rt, root); val c = Ctx(rt)
        assertTrue(Rank.missing(c, root))
        Rank.refresh(c, root)
        assertFalse(Rank.missing(c, root))
        val stored = rt.fileRankGet(root)!!
        val global = Rank.map(c, root, 500u, emptyList(), now)!!
        assertEquals("core.rs", listed(global)[0], global)
        val focused = Rank.map(c, root, 500u, listOf("side.rs"), now)!!
        assertNotEquals(global, focused, "a checkpoint path moves the map")
        fun at(t: String) = listed(t).indexOf("side.rs")
        assertTrue(at(focused) < at(global), "$global\n$focused")
        assertEquals(stored, rt.fileRankGet(root))
        Rank.refresh(c, root)
        assertEquals(stored, rt.fileRankGet(root), "same index, same bytes")
    }

    @Test fun aForeignVersionOrAMissingGraphReadsAsAbsent() = withRuntime("rank-version") { rt ->
        val c = Ctx(rt)
        assertNull(Rank.map(c, "/none", 500u, emptyList(), now))
        rt.fileRankPut("/old", """{"v":0}"""); assertNull(Rank.map(c, "/old", 500u, emptyList(), now))
        rt.fileRankPut("/junk", "not json"); assertNull(Rank.map(c, "/junk", 500u, emptyList(), now))
    }

    @Test fun theMapFillsTheBudgetInOnePassAndStaysUnderIt() = withRuntime("rank-budget") { rt ->
        val root = "/rank/budget"; seedRows(rt, root); val c = Ctx(rt)
        Rank.refresh(c, root)
        val full = Rank.map(c, root, 10_000u, emptyList(), now)!!
        assertEquals(5, listed(full).size)
        val cap = c.estimate(full, Class.Prose) - 1u
        val cut = Rank.map(c, root, cap, emptyList(), now)!!
        assertTrue(listed(cut).size < 5, cut)
        assertTrue(c.estimate(cut, Class.Prose) <= cap, cut)
        assertTrue(full.startsWith(cut), "the cut map is a prefix of the full one")
        assertNull(Rank.map(c, root, 1u, emptyList(), now), "nothing fits")
    }
}
