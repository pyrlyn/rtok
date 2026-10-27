// Port of src/agents/junk_clear.rs: `rtok agents junk clear` with filters (T330.4). The plan is the `list` report's own
// items (no second scanner) cut by `--agent`, `--kind`, `--include review` and `--older-than`, and each item is checked
// again right before it goes. With no filter, or `--agent rtok` alone, the command is T182's run unchanged (T344): new
// deletions need a flag. `stale-worktrees` items come with the worktree port.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.config.HOSTS
import rtok.store.Store
import rtok.store.hostId
import rtok.store.liveAgents
import rtok.store.runRetention
import rtok.sys.ChangePreview
import rtok.sys.EntryKind
import rtok.sys.IoException
import rtok.sys.Meta
import rtok.sys.canon
import rtok.sys.diskUsageUntil
import rtok.sys.fileName
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.moveToTrash
import rtok.sys.pathStartsWith
import rtok.sys.readDir
import rtok.sys.removeTree
import rtok.sys.symlinkMeta
import rtok.worktree.isCacheDir
import rtok.worktree.usageUntil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The `clear` flags that choose what goes. */
data class JunkFilter(
    val agents: List<String> = emptyList(),
    val kinds: List<String> = emptyList(),
    val includeReview: Boolean = false,
    val olderThan: Duration? = null,
    val trash: Boolean = false,
    /** Only items at least this big: what the dry run shows is what `--yes` removes (T330.6). */
    val minSize: Long = 0,
) {
    /** No selecting flag, or `--agent rtok` alone: exactly T182's junk. */
    val isT182: Boolean
        get() = kinds.isEmpty() && !includeReview && olderThan == null && minSize == 0L && agents.all { it == "rtok" }

    internal fun wantsAgent(name: String) = agents.isEmpty() || name in agents

    /** The T330 class gate: `safe` by default, `review` when named or with `--include review`, `explicit` only when named. */
    internal fun wantsKind(
        kind: String,
        cls: String,
    ): Boolean {
        val named = kind in kinds
        if (kinds.isNotEmpty() && !named) return false
        return when (cls) {
            "safe" -> true
            "review" -> named || includeReview
            "explicit" -> named
            else -> false
        }
    }
}

/** One item of the plan and what happened to it. */
class Planned(
    val agent: String,
    val kind: String,
    val path: String,
    val bytes: Long,
    /** Files in it, counted for a planned item before anything is removed. */
    var files: Long,
    /** Unix seconds of the newest file in it; null when unreadable (T330.6). */
    val lastUsed: Long?,
    /** Why it is junk, as `list` says it. */
    val reason: String,
    /** In the plan: false for an item left at planning time (a running agent's). */
    var planned: Boolean,
    /** `clear` (removed, or would be on a dry run) or `skip`. */
    var action: String,
    var note: String,
    /** Planned and not removed: the re-check refused it or the removal failed. */
    var failed: Boolean,
    internal val evidence: String,
    /** The real path at planning time: another one at removal means a link was swapped in. */
    internal val real: String,
) {
    internal fun skip(
        why: String,
        failed: Boolean,
    ) {
        action = "skip"
        note = why
        this.failed = failed
    }

    fun json(): JsonObject =
        buildJsonObject {
            put("agent", agent)
            put("kind", kind)
            put("path", path)
            put("bytes", bytes)
            put("files", files)
            put("last_used", lastUsed?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", reason)
            put("planned", planned)
            put("action", action)
            put("note", note)
            put("failed", failed)
        }

    /** junk_items.rs `Row::from(&Planned)`. */
    fun row(): JunkRow {
        val skipped = action == "skip"
        return JunkRow(agent, kind, "", path, bytes, lastUsed, reason, planned, if (skipped) note else null, if (skipped) "" else note, "", true, true)
    }
}

class Cleared(
    val yes: Boolean,
    val items: List<Planned>,
    val plannedBytes: Long,
    val freedBytes: Long,
) {
    val failed: Boolean get() = items.any { it.failed }

    /** The `--json` shape: serde's field order of `Cleared` and `Planned`. */
    fun json(): JsonObject =
        buildJsonObject {
            put("yes", yes)
            put("items", JsonArray(items.map(Planned::json)))
            put("planned_bytes", plannedBytes)
            put("freed_bytes", freedBytes)
        }
}

object JunkClear {
    /** What `--kind` takes: rtok's own T182 junk, then the T330 kinds. `snapshots` never clears anything. */
    val KINDS =
        listOf(
            "log",
            "archive",
            "cache",
            "temp",
            "logs",
            "build",
            "deps",
            "locks",
            "backups",
            "swap",
            "snapshots",
            "index",
            "crash-dumps",
            "sessions",
            "stale-worktrees",
        )

    /** junk_worktrees.rs `KIND`. */
    const val WORKTREES = "stale-worktrees"

    /** Kinds a running agent may be writing right now; a §22 cache joins them (T342). */
    internal val LIVE_KINDS = listOf("temp", "locks", "swap", "index")

    /** An item touched this recently is in use, whatever the scan said. */
    internal val SETTLE = 60.seconds

    const val RUNNING = "agent running"
    internal const val NANOS = 1_000_000_000L
    private const val DAY = 86_400L

    /** `--agent`'s value: `rtok` or a host id. @throws IllegalArgumentException (a usage error). */
    fun agentArg(s: String): String = if (s == "rtok" || s in HOSTS) s else throw IllegalArgumentException("not an agent: $s (rtok or a host id; see `rtok agents list`)")

    internal fun age(
        now: Long,
        t: Long,
    ): Long = maxOf(now - t, 0)

    /** Archive retention: `--older-than` may only widen `core.retain_calls_days`, and 0 (keep forever) stays 0. */
    internal fun retentionDays(
        cfg: Config,
        f: JunkFilter,
    ): UInt {
        val days = cfg.core.retainCallsDays
        val floor = f.olderThan?.inWholeSeconds?.let { (it + DAY - 1) / DAY } ?: 0
        return if (days == 0u) 0u else maxOf(days, floor.coerceAtMost(UInt.MAX_VALUE.toLong()).toUInt())
    }

    /**
     * The plan: every counted item of [report] the filter wants, each path once (a shared folder goes once). [running]
     * answers per host; it is asked only when an item depends on it. [now] is Unix nanoseconds.
     */
    fun plan(
        cfg: Config,
        report: JunkReport,
        filter: JunkFilter,
        now: Long,
        running: (String) -> Boolean,
    ): List<Planned> {
        val f = if (filter.isT182) filter.copy(agents = listOf("rtok"), kinds = listOf("log", "archive")) else filter
        val planner = Planner(cfg, f, now)
        val out = ArrayList<Planned>()
        for (a in report.agents.filter { f.wantsAgent(it.name) }) {
            if (!a.host) out += planner.own(a.name)
            out += planner.hostItems(a, running)
        }
        // A lockfile inside a folder stays, so the folder does too (T577).
        for (item in out.filter { it.planned }) {
            JunkClearCheck.holdsLockfile(item)?.let { why ->
                item.planned = false
                item.action = "skip"
                item.note = why
            }
        }
        // A folder shared with a running agent stays, whichever owner was listed first.
        val busy = out.filter { !it.planned }.map { it.path }.toSet()
        out.removeAll { it.planned && it.path in busy }
        // An emptied folder keeps its own block on most filesystems, so its size never reaches 0; with nothing left
        // inside it is not worth planning again.
        out.removeAll { runCatching { readDir(it.path).isEmpty() }.getOrDefault(false) }
        val seen = HashSet<String>()
        out.retainAll { seen.add(it.path) }
        return out
    }

    /** One plan's filter, clock and view: what turns a report item into a [Planned]. */
    private class Planner(
        val cfg: Config,
        val f: JunkFilter,
        val now: Long,
    ) {
        private val view = JunkView.of(cfg).copy(now = now / NANOS)

        fun old(path: String): Boolean {
            val o = f.olderThan ?: return true
            return newest(path)?.let { n -> n.time == null || age(now, n.time) >= o.inWholeNanoseconds } == true
        }

        fun item(
            agent: String,
            i: JunkItem,
            note: String?,
            why: String,
        ): Planned {
            val (lastUsed, reason) = JunkItems.stampPlanned(i.kind, i.evidence, i.path, why, view)
            val action = if (note == null) "clear" else "skip"
            return Planned(agent, i.kind, i.path, i.bytes, 0, lastUsed, reason, note == null, action, note.orEmpty(), false, i.evidence, canon(i.path))
        }

        /** rtok's own T182 junk: logs past `[log] files` and archives past the retention. */
        fun own(agent: String): List<Planned> =
            JunkOwn
                .scanWith(cfg, retentionDays(cfg, f))
                .filter { f.wantsKind(it.kind, "safe") && it.bytes >= f.minSize && (it.kind == "archive" || old(it.path)) }
                .map { item(agent, JunkItem(it.kind, "safe", it.path, it.bytes, JunkCache.RTOK_OWN), null, it.note) }

        private fun wanted(i: JunkItem) = i.evidence in DOCUMENTED && i.bytes >= f.minSize && f.wantsKind(i.kind, i.cls) && old(i.path)

        /** A path the user named is no more known to tolerate a running agent than §22's. */
        private fun sensitive(i: JunkItem) = i.kind in LIVE_KINDS || i.evidence == JunkCache.SECTION_22 || i.evidence == JunkReview.EXTRA

        /** The counted items with D36 evidence only, whatever else marked them counted. */
        fun hostItems(
            a: AgentJunk,
            running: (String) -> Boolean,
        ): List<Planned> {
            val live by lazy { running(a.name) }
            return a.items.filter { it.counted && wanted(it) }.map { i ->
                val busy = a.host && sensitive(i) && live
                item(a.name, i, if (busy) RUNNING else null, "")
            }
        }
    }

    internal val DOCUMENTED = setOf(JunkCache.SECTION_22, JunkCache.TAG, JunkCache.RTOK_OWN, JunkReview.EXTRA, JunkCache.GC_VERDICT)

    /** Re-checks and removes every planned item. Archives go through the store's retention with their rows (T182). */
    fun apply(
        cfg: Config,
        items: List<Planned>,
        f: JunkFilter,
        now: Long,
    ) {
        val keep = JunkClearCheck.protected(cfg)
        val archives = ArrayList<Planned>()
        for (p in items.filter { it.action == "clear" }) {
            if (p.kind == "archive") {
                archives += p
                continue
            }
            val refused = JunkClearCheck.recheck(p, keep, now)
            when {
                refused != null -> {
                    p.skip(refused.first, refused.second)
                }

                p.kind == WORKTREES -> {
                    p.skip("worktree removal is not ported yet", true)
                }

                else -> {
                    runCatching { JunkClearCheck.remove(p, f.trash) }
                        .onSuccess { p.note = if (f.trash) "moved to trash" else "removed" }
                        .onFailure { p.skip("failed, kept: ${it.message}", true) }
                }
            }
        }
        if (archives.isEmpty()) return
        val days = retentionDays(cfg, f)
        val done = runCatching { Store.open(cfg.core.dbPath).use { it.runRetention(days, cfg.core.retainHookBodiesDays) } }
        for (p in archives) {
            done.onSuccess { p.note = "removed" }.onFailure { p.skip("failed, kept: ${it.message}", true) }
        }
    }

    /** A host runs when a live rtok session names it (T284) or its binary or app is up; no process probe when sandboxed. */
    fun running(cfg: Config): (String) -> Boolean {
        val store = runCatching { Store.open(cfg.core.dbPath) }.getOrNull()
        val live = store?.let { s -> runCatching { s.liveAgents(cfg.agents.idle) }.getOrNull() }.orEmpty()
        val probe = !HostBins.sandboxed()
        return { id ->
            val hid = store?.let { s -> runCatching { s.hostId(id) }.getOrNull() }
            val bySession = hid != null && live.any { it.hostId == hid }
            bySession || (probe && Hosts.host(id)?.let { hostRunning(cfg, it, RealProcs) } == true)
        }
    }

    /** `clear` beyond T182: scan as `list` does, plan, and with [yes] apply. */
    fun run(
        cfg: Config,
        f: JunkFilter,
        yes: Boolean,
    ): Cleared = runIn(cfg, Junk.report(cfg), f, yes, null)

    /** [run] over a report already taken, cut to [only]: the paths a user was shown and confirmed. */
    fun runIn(
        cfg: Config,
        report: JunkReport,
        f: JunkFilter,
        yes: Boolean,
        only: List<String>?,
    ): Cleared {
        val now = Junk.nowNanos()
        var items = plan(cfg, report, f, now, running(cfg))
        if (only != null) items = items.filter { it.path in only }
        items.filter { it.planned }.forEach { it.files = diskUsageUntil(it.path, null).files }
        if (yes) apply(cfg, items, f, now)
        return summed(items, yes)
    }

    fun summed(
        items: List<Planned>,
        yes: Boolean,
    ): Cleared =
        Cleared(
            yes,
            items,
            items.filter { it.planned }.sumOf { it.bytes },
            items.filter { yes && it.action == "clear" }.sumOf { it.bytes },
        )

    /** Each kind's items as `list` prints them, then planned and freed bytes per agent and kind, then the total. */
    fun toText(
        c: Cleared,
        view: JunkView,
    ): String =
        buildString {
            val h = ChangePreview::humanBytes
            for (g in JunkItems.groups(c.items.map(Planned::row), view)) {
                append(g.lines(view, "  "))
                val of = c.items.filter { it.agent == g.agent && it.kind == g.kind }
                val planned = of.filter { it.planned }.sumOf { it.bytes }
                val freed = of.filter { c.yes && it.action == "clear" }.sumOf { it.bytes }
                val what = if (c.yes) "freed ${h(freed)} of ${h(planned)} planned" else "${h(planned)} planned"
                append("${g.agent} ${g.kind}: $what\n")
            }
            val planned = c.items.filter { it.planned }
            val n = planned.size
            if (!c.yes && n > 0) append(JunkOwn.removalPreview(planned.map { Triple(it.path, it.bytes, it.files) }))
            append(
                when {
                    n == 0 -> "nothing to clear\n"
                    !c.yes -> "dry run: $n items, ${h(c.plannedBytes)} to free, nothing changed; rerun with --yes\n"
                    else -> "Freed ${h(c.freedBytes)} of ${h(c.plannedBytes)} planned\n"
                },
            )
        }
}

/**
 * The newest file mtime under [path] in nanoseconds. [Newest.time] is null for a folder without files; the whole answer
 * is null when [path] is unreadable or the walk did not finish, so an unknown age never reads as old.
 */
internal fun newest(path: String): Newest? {
    val meta = runCatching { symlinkMeta(path) }.getOrNull()
    return when {
        meta == null -> {
            null
        }

        meta.kind != EntryKind.Dir -> {
            Newest(meta.mtimeNanos)
        }

        else -> {
            usageUntil(path, TimeSource.Monotonic.markNow() + JunkCache.AGENT_SCAN_LIMIT).let { (u, cut) ->
                if (cut) null else Newest(u.modified)
            }
        }
    }
}

/** The re-check right before a delete, the protected paths and the lockfile walk (T577) of [JunkClear]. */
internal object JunkClearCheck {
    /** Paths `clear` never removes nor empties a folder around: the store and every host's settings files. */
    internal fun protected(cfg: Config): List<String> {
        val db = cfg.core.dbPath
        val out = listOf("", "-wal", "-shm").map { "$db$it" }.toMutableList()
        for (id in HOSTS) {
            val agent = Agents.agent(id) ?: continue
            Hosts.host(id)?.variants?.forEach { v -> out += agent.markers(cfg, v.kind) }
        }
        return out.map(::canon)
    }

    /**
     * A package-manager lockfile at any depth under [dir], relative to it. A link is never entered. A failure when the
     * walk could not finish (unreadable folder, [deadline]): an unknown answer must not read as "none".
     */
    internal fun lockfileUnder(
        dir: String,
        deadline: TimeSource.Monotonic.ValueTimeMark,
    ): Result<String?> =
        runCatching {
            val stack = ArrayDeque(listOf(dir))
            var found: String? = null
            while (found == null) {
                val d = stack.removeLastOrNull() ?: break
                check(!deadline.hasPassedNow()) { "lockfile scan of $dir timed out" }
                found = lockIn(d, stack)?.removePrefix("$dir/")
            }
            found
        }

    /** A lockfile among [d]'s entries; its folders go on [stack]. A link is never entered. */
    private fun lockIn(
        d: String,
        stack: ArrayDeque<String>,
    ): String? {
        val names = runCatching { readDir(d) }.getOrElse { error("cannot read $d: ${it.message}") }
        for (name in names) {
            val path = join(d, name)
            val kind = runCatching { symlinkMeta(path).kind }.getOrElse { error("cannot check $d: ${it.message}") }
            if (name in JunkKinds.PACKAGE_LOCKS) return path
            if (kind == EntryKind.Dir) stack.addLast(path)
        }
        return null
    }

    /** Why a removal of [p] would also take a lockfile, or could not be ruled out. Worktrees and archives are not walked. */
    internal fun holdsLockfile(p: Planned): String? {
        if (p.kind == "archive" || p.kind == JunkClear.WORKTREES || !isDir(p.path)) return null
        return lockfileUnder(p.path, TimeSource.Monotonic.markNow() + JunkCache.AGENT_SCAN_LIMIT).fold(
            { lock -> lock?.let { "holds a package-manager lockfile: $it" } },
            { e -> "${e.message}: not cleared" },
        )
    }

    /** Why [p] must stay now, and whether that is a failure ("already gone" is not). */
    internal fun recheck(
        p: Planned,
        keep: List<String>,
        now: Long,
    ): Pair<String, Boolean>? {
        val meta =
            try {
                symlinkMeta(p.path)
            } catch (e: IoException) {
                return if (e.kind == IoException.Kind.NotFound) "already gone" to false else "cannot check it: ${e.message}" to true
            }
        return (moved(p, meta, keep) ?: holdsLockfile(p) ?: inUse(p, meta, now))?.let { it to true }
    }

    /** A link, a moved real path, or a protected file: [p] is no longer what was planned. */
    private fun moved(
        p: Planned,
        meta: Meta,
        keep: List<String>,
    ): String? {
        val here = canon(p.path)
        return when {
            meta.kind == EntryKind.Symlink -> "a symlink: never followed"
            here != p.real -> "changed since plan: its real path moved"
            fileName(p.path).orEmpty() in JunkKinds.PACKAGE_LOCKS || keep.any { pathStartsWith(it, here) } -> "is or holds a protected file (store, settings, lockfile)"
            else -> null
        }
    }

    /** An untagged cache, a walk that stopped, a recent write or a held file. */
    private fun inUse(
        p: Planned,
        meta: Meta,
        now: Long,
    ): String? {
        if (p.evidence == JunkCache.TAG && !isCacheDir(p.path)) return "no longer a tagged cache"
        val n = newest(p.path)
        val t = n?.time
        return when {
            n == null -> JunkCache.SCAN_STOPPED
            t != null && (t > now || JunkClear.age(now, t) < JunkClear.SETTLE.inWholeNanoseconds) -> "modified in the last minute"
            meta.kind == EntryKind.File -> heldReason(p.path)
            else -> null
        }
    }

    private fun discard(
        path: String,
        trash: Boolean,
    ) = if (trash) moveToTrash(path) else removeTree(path)

    /** A cache keeps its top folder (an app may expect it) and its `CACHEDIR.TAG` (T330). */
    internal fun remove(
        p: Planned,
        trash: Boolean,
    ) {
        if (p.kind !in listOf("cache", "build", "index")) return discard(p.path, trash)
        for (name in readDir(p.path)) if (name != JunkCache.TAG) discard(join(p.path, name), trash)
    }
}

/** Rust's inner `Option<SystemTime>` of `newest`. */
internal data class Newest(
    val time: Long?,
)
