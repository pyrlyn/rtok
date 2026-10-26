@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package rtok.plugins.graph

import rtok.config.Graph
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.LinkKind
import rtok.store.Origin
import rtok.store.linkProjects
import rtok.store.registerProject
import rtok.support.withRuntime
import rtok.sys.*
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class WatchTest {
    private val reindexCap = 10.seconds
    private val pollMs = 50L

    private fun sleepMs(ms: Long) = platform.posix.usleep((ms * 1000).toUInt())

    private fun mk(
        tag: String,
        watch: String = "off",
        body: (Runtime, String) -> Unit,
    ) = withRuntime(tag, { it.copy(plugins = it.plugins.copy(graph = it.plugins.graph.copy(autoIndex = false, watch = watch))) }) { rt ->
        val dir = canonicalize(rtok.support.tmpDir(tag))!!
        body(rt, dir)
        removeDirAll(dir)
    }

    private fun put(
        dir: String,
        rel: String,
        text: String,
    ) {
        parent(join(dir, rel))?.let(::createDirAll)
        writeString(join(dir, rel), text)
    }

    private fun waitContains(
        cx: Ctx,
        dir: String,
        name: String,
        needle: String,
    ): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + reindexCap
        while (true) {
            if (Tools.symbol(cx, dir, name).contains(needle)) return true
            if (deadline.hasPassedNow()) return false
            sleepMs(pollMs)
        }
    }

    /** Rewrites a probe until the watcher indexes it, so the timed write after measures the watcher, not start-up. */
    private fun warm(
        cx: Ctx,
        dir: String,
    ) {
        val probe = join(dir, "warm.rs")
        val cap = TimeSource.Monotonic.markNow() + reindexCap
        var n = 0
        while (!cap.hasPassedNow()) {
            writeString(probe, "pub fn warm_probe() {}\n// ${n++}\n")
            val retry = TimeSource.Monotonic.markNow() + (2 * Watch.QUIET_MS).milliseconds
            while (!retry.hasPassedNow() && !Tools.symbol(cx, dir, "warm_probe").contains("warm.rs:1")) sleepMs(pollMs)
            if (Tools.symbol(cx, dir, "warm_probe").contains("warm.rs:1")) break
        }
        removeFile(probe)
        assertTrue(waitContains(cx, dir, "warm_probe", "no definition of warm_probe"), "probe rows stayed")
    }

    private fun matcher(
        dir: String,
        cfg: Graph = Graph(),
    ) = Matcher(dir, cfg)

    /** Events fed straight to `pump` for one quiet window past the [want]th run; returns the run count. */
    private fun pumpEvents(
        rt: Runtime,
        dir: String,
        sent: List<String>,
        want: Int,
    ): Int {
        val stop = AtomicInt(0)
        val runs = AtomicInt(0)
        val q = FsEventQueue()
        sent.forEach { q.push(FsEvent(listOf(it))) }
        val w = Worker.start(name = "pump-test")
        val args = Triple(rt, dir, Triple(stop, runs, q))
        val done = w.execute(TransferMode.SAFE, { args }) { (r, d, t) -> Watch.pump(Ctx(r), d, t.first, t.second, t.third) }
        val cap = TimeSource.Monotonic.markNow() + reindexCap
        while (runs.value < want && !cap.hasPassedNow()) sleepMs(pollMs)
        sleepMs(Watch.QUIET_MS + 4 * pollMs)
        stop.value = 1
        done.result
        w.requestTermination().result
        return runs.value
    }

    @Test fun eventsInsideANestedLinkedWorktreeAreIgnored() =
        mk("watch-nested") { _, dir ->
            val nested = join(dir, ".rtok/worktrees/x")
            val admin = join(dir, ".git/worktrees/x")
            createDirAll(admin)
            createDirAll(join(nested, "src"))
            writeString(join(admin, "commondir"), "../..\n")
            writeString(join(nested, ".git"), "gitdir: $admin\n")
            val sub = join(dir, "mods/sub")
            createDirAll(sub)
            writeString(join(sub, ".git"), "gitdir: ../../.git/modules/sub\n")
            put(nested, "src/n.rs", "fn n() {}\n")
            put(sub, "s.rs", "fn s() {}\n")
            put(dir, "own.rs", "fn own() {}\n")
            val ig = Watch.gitignore(dir)
            val m = matcher(dir)
            for (p in listOf(join(nested, "src/n.rs"), join(nested, "src"), nested)) {
                val st = Watch.State()
                assertFalse(Watch.absorbEvent(p, ig, m, st), p)
                assertTrue(st.pending.isEmpty() && !st.rescan, p)
            }
            for (p in listOf(join(dir, "own.rs"), join(sub, "s.rs"))) {
                val st = Watch.State()
                assertTrue(Watch.absorbEvent(p, ig, m, st), p)
                assertEquals(1, st.pending.size, p)
            }
        }

    @Test fun watcherReindexesNewFileWhileCallsReadNothing() =
        mk("watch-new", "notify") { rt, dir ->
            // The store is in memory; watch a sibling tree anyway, as the Rust test does.
            val root = join(dir, "proj")
            put(root, "lib.rs", "pub fn seed() {}\n")
            Index.run(Ctx(rt), root, false)
            val stop = AtomicInt(0)
            val w = Worker.start(name = "watch-test")
            val args = Triple(rt, root, stop)
            val done = w.execute(TransferMode.SAFE, { args }) { (r, d, s) -> Watch.run(Ctx(r), d, s) }
            val cx = Ctx(rt)
            sleepMs(80)
            warm(cx, root)
            put(root, "watched.rs", "pub fn watched() {}\n")
            val found = waitContains(cx, root, "watched", "watched.rs:1")
            val read = GraphCore.indexFor(cx, root).read
            sleepMs(Watch.QUIET_MS + pollMs)
            removeFile(join(root, "watched.rs"))
            val gone = waitContains(cx, root, "watched", "no definition of watched")
            stop.value = 1
            done.result
            w.requestTermination().result
            assertTrue(found, "watcher did not re-index: ${Tools.symbol(cx, root, "watched")}")
            assertEquals(0, read, "the call itself must open no file")
            assertTrue(gone, "deleted file kept its rows")
        }

    @Test fun anEditInALinkedProjectUpdatesItsIndex() =
        mk("watch-scope", "notify") { rt, dir ->
            val ids =
                listOf("a", "b", "c").map { n ->
                    val root = join(dir, n)
                    put(root, "lib.rs", "pub fn seed() {}\n")
                    Index.run(Ctx(rt), root, false)
                    rt.store.registerProject(root, Origin.Manual).id
                }
            rt.store.linkProjects(ids[0], ids[1], LinkKind.Manual, null)
            rt.store.linkProjects(ids[1], ids[2], LinkKind.Manual, null)
            val c = join(dir, "c")
            val stop = AtomicInt(0)
            val w = Worker.start(name = "scope-test")
            val args = Triple(rt, join(dir, "a"), stop)
            val done = w.execute(TransferMode.SAFE, { args }) { (r, d, s) -> Watch.runScope(r, d, s) }
            val cx = Ctx(rt)
            sleepMs(80)
            warm(cx, c)
            put(c, "edited.rs", "pub fn edited() {}\n")
            val found = waitContains(cx, c, "edited", "edited.rs:1")
            sleepMs(Watch.QUIET_MS + pollMs)
            removeFile(join(c, "edited.rs"))
            val gone = waitContains(cx, c, "edited", "no definition of edited")
            stop.value = 1
            done.result
            w.requestTermination().result
            assertTrue(found, "watcher did not re-index the linked project")
            assertTrue(gone, "a deleted file in the linked project kept its rows")
        }

    @Test fun pendingPathsAreKeptPerRoot() =
        mk("watch-pending") { rt, _ ->
            val cx = Ctx(rt)
            cx.publishGraphWatchPending("/p/a", listOf("x.rs"))
            cx.publishGraphWatchPending("/p/b", listOf("y.rs"))
            assertEquals(listOf("x.rs"), cx.graphWatchPending("/p/a"))
            cx.publishGraphWatchPending("/p/a", emptyList())
            assertTrue(cx.graphWatchPending("/p/a").isEmpty())
            assertEquals(listOf("y.rs"), cx.graphWatchPending("/p/b"))
        }

    @Test fun burstsCoalesceToFewRuns() =
        mk("watch-burst", "notify") { rt, dir ->
            put(dir, "lib.rs", "pub fn seed() {}\n")
            Index.run(Ctx(rt), dir, false)
            val burst = join(dir, "burst.rs")
            val q = FsEventQueue()
            val stop = AtomicInt(0)
            val runs = AtomicInt(0)
            val w = Worker.start(name = "burst-test")
            val args = Triple(rt, dir, Triple(stop, runs, q))
            val done = w.execute(TransferMode.SAFE, { args }) { (r, d, t) -> Watch.pump(Ctx(r), d, t.first, t.second, t.third) }
            for (i in 0 until 200) {
                writeString(burst, "pub fn f$i() {}\n")
                q.push(FsEvent(listOf(burst)))
            }
            sleepMs(Watch.QUIET_MS + 80)
            stop.value = 1
            done.result
            w.requestTermination().result
            assertTrue(runs.value <= 3, "200 writes coalesced to ${runs.value} runs, want ≤ 3")
        }

    @Test fun gitAndUnsupportedPathsAreIrrelevant() {
        val m = matcher(".")
        assertFalse(Watch.relevant(".git/HEAD", m))
        assertFalse(Watch.relevant("foo/bar.md", m))
        assertTrue(Watch.relevant("src/lib.rs", m))
    }

    @Test fun unsupportedFileDoesNotClearPendingOrRescan() =
        mk("watch-db-noise") { _, dir ->
            writeString(join(dir, "rtok.db"), "x")
            val st = Watch.State(pending = hashSetOf(join(dir, "watched.rs")))
            val ig = Gitignore(dir, "")
            val m = matcher(dir)
            assertFalse(Watch.absorbEvent(join(dir, "rtok.db"), ig, m, st))
            assertTrue(!st.rescan && st.pending.size == 1, "existing unsupported file triggered a rescan")
            assertTrue(Watch.absorbEvent(join(dir, "src/module"), ig, m, st))
            assertTrue(st.rescan && st.pending.isEmpty(), "removed directory must rescan")
        }

    @Test fun gitignoredEventIsDropped() =
        mk("watch-gitignore") { _, dir ->
            writeString(join(dir, ".gitignore"), "target/\n")
            val ig = Watch.gitignore(dir)
            val m = matcher(dir)
            val st = Watch.State()
            assertFalse(Watch.absorbEvent(join(dir, "target/debug/build/x/out/y.rs"), ig, m, st))
            assertTrue(st.pending.isEmpty() && !st.rescan)
            assertTrue(Watch.absorbEvent(join(dir, "src/lib.rs"), ig, m, st))
            assertEquals(1, st.pending.size)
        }

    @Test fun eventsFollowTheWalksIncludeAndExcludeRules() =
        mk("watch-eligibility") { _, dir ->
            createDirAll(join(dir, ".git"))
            writeString(join(dir, ".gitignore"), "wanted/\nunwanted/\n")
            val cases =
                listOf(
                    "wanted/a.rs" to true,
                    "wanted/generated/g.rs" to false,
                    "unwanted/a.rs" to false,
                    "src/generated/g.rs" to false,
                    "src/lib.rs" to true,
                )
            for ((p, _) in cases) put(dir, p, "fn f() {}\n")
            val m = matcher(dir, Graph(include = listOf("wanted/"), exclude = listOf("**/generated/**")))
            val ig = Watch.gitignore(dir)
            val walked =
                m
                    .walk()
                    .filter { it.isFile && m.indexable(it.path) }
                    .map { it.path }
                    .toSet()
            for ((rel, want) in cases) {
                val st = Watch.State()
                val path = join(dir, rel)
                val taken = Watch.absorbEvent(path, ig, m, st)
                assertEquals(want to want, taken to (path in st.pending), path)
                assertEquals(want, path in walked, "walk disagrees on $path")
            }
        }

    @Test fun settleRunsOnceAfterQuietAndNeverWhenClean() =
        mk("watch-settle") { rt, dir ->
            put(dir, "a.rs", "pub fn settled() {}\n")
            val cx = Ctx(rt)
            val key = Index.canon(dir)
            val runs = AtomicInt(0)
            val st = Watch.State(pending = hashSetOf(join(dir, "a.rs")))
            Watch.settle(cx, dir, runs, st)
            assertEquals(Triple(0, false, false), Triple(runs.value, st.pending.isEmpty(), st.rescan), "ran inside QUIET")
            st.last = TimeSource.Monotonic.markNow() - Watch.QUIET_MS.milliseconds
            Watch.settle(cx, dir, runs, st)
            assertEquals(Triple(1, true, false), Triple(runs.value, st.pending.isEmpty(), st.rescan))
            assertEquals(1, cx.symbolDefs(key, "settled").size)
            st.last = TimeSource.Monotonic.markNow() - Watch.QUIET_MS.milliseconds
            Watch.settle(cx, dir, runs, st)
            assertEquals(1, runs.value, "a clean state ran again")
        }

    @Test fun irrelevantEventsNeverRunAndABurstRunsOnce() =
        mk("watch-noise") { rt, dir ->
            put(dir, "a.rs", "pub fn seed() {}\n")
            assertEquals(0, pumpEvents(rt, dir, listOf(".git/index", ".git/HEAD").map { join(dir, it) }, 0))
            assertEquals(1, pumpEvents(rt, dir, List(50) { join(dir, "a.rs") }, 1))
        }

    @Test fun removedDirectoryDropsIndexRows() =
        mk("watch-rmdir", "notify") { rt, dir ->
            val module = join(dir, "src/module")
            put(module, "gone.rs", "pub fn vanished() {}\n")
            val cx = Ctx(rt)
            Index.run(cx, dir, false)
            val key = Index.canon(dir)
            assertEquals(1, cx.symbolDefs(key, "vanished").size)
            removeDirAll(module)
            assertEquals(1, pumpEvents(rt, dir, listOf(module), 1), "directory removal must re-index once")
            assertTrue(cx.symbolDefs(key, "vanished").isEmpty(), "deleted directory kept its rows")
            assertEquals(0, GraphCore.indexFor(cx, dir).read, "the call that follows must not walk")
        }

    @Test fun editAndRenameEventsReindexOnceEach() =
        mk("watch-pump") { rt, dir ->
            put(dir, "a.rs", "pub fn old_name() {}\n")
            val cx = Ctx(rt)
            Index.run(cx, dir, false)
            val key = Index.canon(dir)

            fun defs(n: String) = cx.symbolDefs(key, n).map { it.a }
            // A different length, so the (mtime, size) gate cannot call the edit unchanged.
            put(dir, "a.rs", "pub fn new_name_longer() {}\n")
            assertEquals(1, pumpEvents(rt, dir, listOf(join(dir, "a.rs")), 1))
            assertEquals(listOf("a.rs"), defs("new_name_longer"))
            assertTrue(defs("old_name").isEmpty(), "edit kept the old definition")
            check(platform.posix.rename(join(dir, "a.rs"), join(dir, "b.rs")) == 0)
            assertEquals(1, pumpEvents(rt, dir, listOf(join(dir, "a.rs"), join(dir, "b.rs")), 1))
            assertEquals(listOf("b.rs"), defs("new_name_longer"), "rename kept a.rs rows")
        }

    @Test fun pumpEndsOnStopOrOnADeadChannel() =
        mk("watch-end") { rt, dir ->
            val cx = Ctx(rt)
            val runs = AtomicInt(0)
            val q = FsEventQueue()
            Watch.pump(cx, dir, AtomicInt(1), runs, q)
            q.end()
            Watch.pump(cx, dir, AtomicInt(0), runs, q)
            assertEquals(0, runs.value)
        }
}
