// Port of src/agents/junk.rs: `rtok agents junk clear` for rtok's own log siblings and archives (T182, `scan`, `run`,
// `to_table`) and the report `agents junk list` prints (T330.1, T330.2): every installed host's folders from the
// `junk_map` of `research.md` §22, read-only, sized with a per-agent time limit, and the items `clear` may remove.
// `reportInRepo` adds the agent worktrees `build` looks in and the `stale-worktrees` gc would remove (JunkWorktrees.kt).
package rtok.agents

import rtok.Col
import rtok.config.Config
import rtok.config.HOSTS
import rtok.plugins.graph.Lsp
import rtok.store.Store
import rtok.store.archivesPendingRetention
import rtok.store.convertToIncrementalVacuum
import rtok.store.projects
import rtok.store.runRetention
import rtok.sys.ChangePreview
import rtok.sys.EntryKind
import rtok.sys.canonicalize
import rtok.sys.cwd
import rtok.sys.diskUsageUntil
import rtok.sys.fileName
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.listDirOrEmpty
import rtok.sys.parent
import rtok.sys.pathStartsWith
import rtok.sys.removeFile
import rtok.sys.symlinkMeta
import rtok.table
import rtok.worktree.notedTable
import kotlin.time.Duration
import kotlin.time.TimeSource

/** One file `clear` takes from rtok's own home. */
data class JunkOutcome(
    /** `log` or `archive`. */
    val kind: String,
    val path: String,
    val bytes: Long,
    /** A log sibling or archive is one file. */
    val files: Long = 1,
    /** `clear` or `keep` (only after `--yes` failed to remove it). */
    var action: String = "clear",
    var note: String,
    var failed: Boolean = false,
)

/** One folder an agent writes to, with its disk usage. */
data class Folder(
    val path: String,
    /** `data`, `temp`, `logs` or `cache` ([JunkRole]); rtok's own folders are `data`. */
    val role: String,
    val sizeBytes: Long,
    /** A row of `research.md` §22 backs this path (D36); anything else is listed read-only. */
    val documented: Boolean,
    /** The other agents whose folder resolves to this same directory. It is sized once. */
    val sharedWith: List<String> = emptyList(),
    /** Why the size may be low or the path is not what it looks like. */
    val note: String? = null,
)

/** One junk kind under an agent: what `clear` would remove, summed. */
data class KindRow(
    val kind: String,
    /** `safe`: always regenerated, cleared by default (T330 classes). */
    val cls: String,
    val items: Int,
    val sizeBytes: Long,
)

data class AgentJunk(
    val name: String,
    /** A host from `HOSTS`; `rtok` itself is the one row that is not. */
    val host: Boolean,
    val installed: Boolean,
    val folders: List<Folder>,
    /** Disk usage of the folders, a folder nested in another one counted once. */
    val totalBytes: Long,
    val kinds: List<KindRow>,
    /** Every directory `clear` may empty (T330.3); only an item without a reason is in [kinds] and the totals. */
    val items: List<JunkItem>,
    /** Lines `list` prints under the kinds: the session threshold and the host's own session retention (T330.5.2). */
    val notes: List<String>,
    val freedDefaultBytes: Long,
    /** What `--include review` would free on top of the `safe` kinds (T330.5.1). */
    val freedReviewBytes: Long,
)

/** `rtok agents junk list` (T330.1): per-agent folders, junk kinds and space `clear` frees. */
data class JunkReport(
    val agents: List<AgentJunk>,
    /** rtok's own log and archive files `clear` would remove; the breakdown lists them from here (not in JSON). */
    val own: List<JunkOutcome>,
    /** Every folder once, however many agents share it. */
    val totalBytes: Long,
    val freedDefaultBytes: Long,
    val freedReviewBytes: Long,
)

/** Which hosts a [Junk.reportWith] covers and the T152 rules a cache is judged by. */
data class JunkOptions(
    /** Also hosts that are not installed. */
    val all: Boolean = false,
    /** A tagged or language-server cache modified within this window stays (T152, T342). */
    val idle: Duration = JunkCache.DEFAULT_IDLE,
    /** Where the command runs: a tagged cache of that worktree stays. */
    val cwd: String = cwd(),
    /** Unix nanoseconds. */
    val now: Long = Junk.nowNanos(),
    /** The agent worktrees `build` looks in, as `(host, path)`. Empty unless the caller lists them. */
    val worktrees: List<Pair<String, String>> = emptyList(),
    /** The `stale-worktrees` items (T330.5.3) with the agent row each belongs to. */
    val staleWorktrees: List<Pair<String, JunkItem>> = emptyList(),
)

object Junk {
    /** The junk kinds in the order the T330 tables list them. */
    val KINDS =
        listOf(
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

    private const val NANOS = 1_000_000_000L

    fun nowNanos(): Long =
        kotlin.time.Clock.System
            .now()
            .let { it.epochSeconds * NANOS + it.nanosecondsOfSecond }

    /** The directories rtok's junk lives in; one nested inside another is folded into the outer. */
    private fun rtokFolders(
        cfg: Config,
        cache: String,
    ): List<Folder> {
        val dirs = listOfNotNull(parent(cfg.log.path), cfg.core.archiveDir, cache).filter(::isDir)
        return outermost(dirs).map { d ->
            Folder(d, if (d == cache) "cache" else "data", diskUsageUntil(d, null).bytes, documented = true)
        }
    }

    /** The cache directories rtok writes: its project LSP state (idle rule), its platform cache dir, stale plugin copies (T279). */
    private fun rtokOwned(
        cfg: Config,
        roots: JunkRoots,
        cache: String,
    ): List<OwnedCache> =
        listOf(OwnedCache(cache, JunkCache.RTOK_OWN, idleRule = false)) +
            projectLspDirs(cfg, listOf("cache", "pub-cache")) +
            stagingCaches(roots.resolve("{claude}"))

    /** The [subdirs] of the language-server state rtok confines to each registered project. */
    private fun projectLspDirs(
        cfg: Config,
        subdirs: List<String>,
    ): List<OwnedCache> {
        val projects = runCatching { Store.open(cfg.core.dbPath).use { it.projects() } }.getOrDefault(emptyList())
        return projects.flatMap { p ->
            val state = Lsp.stateRoot(p.root)
            subdirs.map { OwnedCache(join(state, it), JunkCache.RTOK_OWN, idleRule = true) }
        }
    }

    /** [paths] without any that sits inside another one, so a directory is walked once. */
    internal fun outermost(paths: List<String>): List<String> {
        val all = paths.sorted().distinct()
        return all.filter { d -> all.none { o -> o != d && pathStartsWith(d, o) } }
    }

    /** One row per kind and class of [items]: only an item `clear` would take now counts. */
    internal fun kindRows(items: List<JunkItem>): List<KindRow> =
        KINDS.flatMap { kind ->
            listOf("safe", "review", "explicit").mapNotNull { cls ->
                val counted = items.filter { it.kind == kind && it.cls == cls && it.counted }
                if (counted.isEmpty()) null else KindRow(kind, cls, counted.size, counted.sumOf { it.bytes })
            }
        }

    /** What `clear` frees: the `safe` rows, plus the `review` ones with [review]. */
    internal fun freed(
        rows: List<KindRow>,
        review: Boolean,
    ) = rows.filter { it.cls == "safe" || (review && it.cls == "review") }.sumOf { it.sizeBytes }

    /** The sum of the folders not inside another listed folder. */
    internal fun topLevelBytes(folders: List<Pair<String, Long>>): Long {
        val all = folders.map { it.first }
        return folders.filter { (p, _) -> all.none { o -> o != p && pathStartsWith(p, o) } }.sumOf { it.second }
    }

    /** Read-only: the same [scan] `clear` runs, summed per kind for rtok, plus every installed host's folders. */
    fun report(cfg: Config): JunkReport = reportInRepo(cfg, false)

    /**
     * [report] over the repository the command runs in, for [all] hosts or only the installed ones: the agent worktrees
     * `build` looks in and the `stale-worktrees` gc would remove.
     */
    fun reportInRepo(
        cfg: Config,
        all: Boolean,
    ): JunkReport {
        val opts = JunkOptions(all = all)
        val found = JunkWorktrees.worktrees(cfg, opts.cwd)
        val stale = JunkWorktrees.items(cfg, opts.cwd, found, opts.now, JunkCache.AGENT_SCAN_LIMIT)
        val options = opts.copy(worktrees = JunkWorktrees.inHosts(found), staleWorktrees = stale)
        return reportWith(cfg, JunkRoots.fromEnv(), options, JunkCache.AGENT_SCAN_LIMIT)
    }

    @Suppress("LongMethod")
    fun reportWith(
        cfg: Config,
        roots: JunkRoots,
        opts: JunkOptions,
        limit: Duration,
    ): JunkReport {
        val cx = JunkCtx(opts.cwd, opts.idle, opts.now)
        val outcomes = JunkOwn.scan(cfg)
        val kinds =
            listOf("log", "archive")
                .mapNotNull { kind ->
                    val rows = outcomes.filter { it.kind == kind }
                    if (rows.isEmpty()) null else KindRow(kind, "safe", rows.size, rows.sumOf { it.bytes })
                }.toMutableList()
        val rtokCache = roots.resolve("{rtok_cache}")
        val rtokFolders = rtokFolders(cfg, rtokCache)
        val tagRoots = rtokFolders.map { it.path }
        val junk = cfg.agents.junk
        val (extraOwned, extra) = extraItems(junk, "rtok", cfg.home, roots.home, cx, limit)
        val owned = rtokOwned(cfg, roots, rtokCache) + extraOwned
        val cache = ArrayList(cacheItems(owned, outermost(tagRoots), cx, limit))
        cache += extra
        // The language servers' data and state hold their index: review, the idle rule applies as for the caches.
        cache += cacheItems(projectLspDirs(cfg, listOf("data", "state")), emptyList(), cx, limit).map { it.copy(kind = "index", cls = "review") }
        cache += JunkHosts.staleFor(opts.staleWorktrees, "rtok")
        exclude(cache, junk.exclude, cfg.home, roots.home, limit)
        val kept = dropNested(cache)
        kinds += kindRows(kept)
        val agents = ArrayList<AgentJunk>()
        agents +=
            AgentJunk(
                "rtok",
                false,
                true,
                rtokFolders,
                rtokFolders.sumOf { it.sizeBytes },
                kinds,
                kept,
                emptyList(),
                freed(kinds, false),
                freed(kinds, true),
            )
        val (hosts, byKey) = JunkHosts.hostRows(cfg, roots, opts, limit, cx)
        agents += hosts
        for (f in agents[0].folders) byKey[f.path] = f.sizeBytes
        val totalBytes = topLevelBytes(byKey.entries.sortedBy { it.key }.map { it.key to it.value })
        val logArchive = agents[0].kinds.filter { it.kind !in KINDS }.sumOf { it.sizeBytes }

        // Each path once however many agents list it, and a nested one inside its parent.
        fun freedAll(review: Boolean): Long {
            val byPath = LinkedHashMap<String, Long>()
            agents
                .flatMap { it.items }
                .filter { it.counted && (it.cls == "safe" || (review && it.cls == "review")) }
                .forEach { byPath[it.path] = it.bytes }
            return logArchive + topLevelBytes(byPath.entries.sortedBy { it.key }.map { it.key to it.value })
        }
        val (defaultBytes, reviewBytes) = freedAll(false) to freedAll(true)
        // T343: agents by space freed, largest first; stable, so rtok keeps the lead on a tie.
        val sorted = agents.sortedByDescending { it.freedDefaultBytes }
        return JunkReport(sorted, outcomes, totalBytes, defaultBytes, reviewBytes)
    }
}
