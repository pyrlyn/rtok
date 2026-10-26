// Port of src/plugins/graph/watch.rs: background re-index of the graph store, one writer (D18) in `rtok mcp`.
// One watcher per project of the scope (T329.5); events are debounced for [Watch.QUIET_MS], then only the event
// paths are indexed (T35.4) unless the OS lost events or a directory changed, which re-walks the root.
@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package rtok.plugins.graph

import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.plugins.read.walkRootOk
import rtok.project.Refs
import rtok.sys.FsWatch
import rtok.sys.FsWatchClosed
import rtok.sys.Gitignore
import rtok.sys.IoException
import rtok.sys.ancestors
import rtok.sys.components
import rtok.sys.eprint
import rtok.sys.exists
import rtok.sys.extension
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.pathStartsWith
import rtok.sys.readToString
import rtok.sys.watchRecursive
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

object Watch {
    const val QUIET_MS = 250L
    const val PENDING_CAP = 1024
    const val TICK_MS = 50L

    /** How often the scope is read again, so a link made after start-up is watched from the next round. */
    const val RESCOPE_MS = 2_000L

    /** The debounce state of one root. */
    class State(
        var last: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
        val pending: MutableSet<String> = HashSet(),
        var rescan: Boolean = false,
    )

    private fun report(
        cx: Ctx,
        level: String,
        msg: String,
    ) {
        eprint("watch: $msg\n")
        runCatching { cx.log(level, "graph", "watch", msg) }
    }

    /** Watches [root] until [stop] is set (non-zero) or the OS watch dies; [runs] counts index runs. */
    fun run(
        cx: Ctx,
        root: String,
        stop: AtomicInt,
        runs: AtomicInt = AtomicInt(0),
    ) {
        val w =
            try {
                watchRecursive(root)
            } catch (e: IoException) {
                return report(cx, "error", "watch $root: ${e.message}")
            }
        w.use { pump(cx, root, stop, runs, it) }
    }

    /** T329.5: one watcher per project of [start]'s scope; an unwatchable root is logged and skipped. */
    fun runScope(
        rt: Runtime,
        start: String,
        stop: AtomicInt,
    ) {
        val cx = Ctx(rt)
        val watched = HashSet<String>()
        val workers = ArrayList<Worker>()
        while (stop.value == 0) {
            for (m in runCatching { Scope.resolve(rt.store, null, start) }.getOrDefault(emptyList())) {
                // Re-offered every round while missing, so a directory that returns is picked up.
                if (!isDir(m.root) || !watched.add(m.root)) continue
                // T263: never watch `/` or the home directory.
                val refused = runCatching { walkRootOk(m.root) }.exceptionOrNull()
                if (refused != null) {
                    report(cx, "warn", "watcher skipped for ${m.root}: ${refused.message}")
                    continue
                }
                val w = Worker.start(name = "graph-watch")
                workers += w
                val args = Triple(rt, m.root, stop)
                w.execute(TransferMode.SAFE, { args }) { (r, root, s) -> run(Ctx(r), root, s) }
            }
            val round = TimeSource.Monotonic.markNow()
            while (round.elapsedNow().inWholeMilliseconds < RESCOPE_MS && stop.value == 0) platform.posix.usleep((TICK_MS * 1000).toUInt())
        }
        workers.forEach { it.requestTermination().result }
    }

    /** The debounce loop, apart from the OS watcher so tests can feed it events. Ends on [stop] or a dead watch. */
    fun pump(
        cx: Ctx,
        root: String,
        stop: AtomicInt,
        runs: AtomicInt,
        events: FsWatch,
    ) {
        val ig = gitignore(root)
        val matcher = Matcher(root, graphCfg(cx))
        val st = State()
        while (stop.value == 0) {
            val ev =
                try {
                    events.next(TICK_MS)
                } catch (_: FsWatchClosed) {
                    break
                }
            if (ev != null) {
                if (ev.rescan) {
                    st.rescan = true
                    st.pending.clear()
                }
                var touched = false
                for (p in ev.paths) if (absorbEvent(p, ig, matcher, st)) touched = true
                if (touched) st.last = TimeSource.Monotonic.markNow()
                if (st.pending.size > PENDING_CAP) {
                    st.rescan = true
                    st.pending.clear()
                }
            }
            syncPending(cx, root, st.pending)
            settle(cx, root, runs, st)
        }
    }

    /** Once per quiet burst: index the event paths, or re-walk on a rescan. */
    fun settle(
        cx: Ctx,
        root: String,
        runs: AtomicInt,
        st: State,
    ) {
        if (st.last.elapsedNow() < QUIET_MS.milliseconds || (!st.rescan && st.pending.isEmpty())) return
        if (st.rescan) runCatching { Index.run(cx, root, false) } else runCatching { Index.runChanged(cx, root, st.pending.toSet()) }
        runs.incrementAndGet()
        st.last = TimeSource.Monotonic.markNow()
        syncPending(cx, root, st.pending)
        st.pending.clear()
        cx.publishGraphWatchPending(Index.canon(root), emptyList())
        st.rescan = false
    }

    /** The root's `.gitignore` only (nested ones are not read, like the Rust watcher). */
    fun gitignore(root: String): Gitignore = Gitignore(root, runCatching { readToString(join(root, ".gitignore")) }.getOrDefault(""))

    /** `matched_path_or_any_parents(..).is_ignore()`: the path, then its parents below the root, first match wins. */
    private fun ignored(
        ig: Gitignore,
        root: String,
        p: String,
    ): Boolean {
        ig.matched(p, isDir(p))?.let { return it }
        for (a in ancestors(p).drop(1)) {
            if (!pathStartsWith(a, root) || a.length <= root.trimEnd('/').length) break
            ig.matched(a, true)?.let { return it }
        }
        return false
    }

    private fun gitPath(p: String) = ".git" in components(p)

    fun relevant(
        p: String,
        matcher: Matcher,
    ) = matcher.indexable(p) && !gitPath(p)

    /**
     * Source files go to pending. A directory (present, or just removed and extension-less) forces a rescan so
     * vanished children drop (T36.17); other files (store, WAL, logs, docs) are ignored.
     */
    fun absorbEvent(
        p: String,
        ig: Gitignore,
        matcher: Matcher,
        st: State,
        root: String = matcher.root,
    ): Boolean {
        // An explicit `include` revives an ignored path in the full walk, so it must here too.
        if (gitPath(p) || matcher.inLinkedWorktree(p) || (ignored(ig, root, p) && !matcher.isIncluded(p))) return false
        if (relevant(p, matcher)) {
            st.pending += p
            return true
        }
        if (isDir(p) || (!exists(p) && extension(p) == null)) {
            st.rescan = true
            st.pending.clear()
            return true
        }
        return false
    }

    private fun syncPending(
        cx: Ctx,
        root: String,
        pending: Set<String>,
    ) {
        val out = pending.filter { it.startsWith('/') }.map { Refs.diff(it, root) }.sorted()
        cx.publishGraphWatchPending(Index.canon(root), out)
    }
}
