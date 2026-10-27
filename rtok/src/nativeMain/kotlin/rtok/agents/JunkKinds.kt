// Port of src/agents/junk_kinds.rs: the `temp`, `build`, `deps`, `locks`, `swap`, `index` and `backups` junk kinds and
// rtok's plugin staging caches, on top of the `cache` item model of JunkCache.kt. D36: only a path with evidence may be
// cleared; a lock or swap file has none, so it is listed read-only with the reason a live owner would give first.
// Nothing here deletes. `agent_worktrees`/`in_hosts` come with junk_worktrees.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.agent.sdk.isBackupDir
import rtok.json.StrictJson
import rtok.sys.EntryKind
import rtok.sys.canon
import rtok.sys.entryKind
import rtok.sys.fileName
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.listDirOrEmpty
import rtok.sys.parent
import rtok.sys.pathStartsWith
import rtok.sys.readToString
import rtok.sys.symlinkMeta
import rtok.worktree.Cache
import rtok.worktree.Policy
import rtok.worktree.keptBecause
import rtok.worktree.usageUntil
import kotlin.time.Duration
import kotlin.time.TimeSource

object JunkKinds {
    /** Why a find with no D36 evidence is not cleared. */
    const val NOT_DOCUMENTED = "not documented: not cleared (add to [agents.junk] extra to clear)"

    /** The evidence column of a find nothing documents. */
    const val NO_EVIDENCE = "none"

    /** How deep a walk for lock, swap and build leftovers goes. */
    internal const val MAX_DEPTH = 12

    /** Never entered: history, and a dependency tree that is not the agent's state. */
    internal val SKIP_DIRS = listOf(".git", "node_modules")

    /** Build output without a `CACHEDIR.TAG`: listed in an agent worktree, never cleared (D36). */
    internal val BUILD_DIRS = listOf("dist", ".next", "__pycache__")

    /** Reinstallable dependency folders and the files beside one that can reinstall it. */
    internal val DEPS: List<Pair<String, List<String>>> =
        listOf(
            "node_modules" to listOf("package.json"),
            ".venv" to listOf("pyproject.toml", "requirements.txt", "uv.lock", "Pipfile"),
            "vendor" to listOf("composer.json", "Gemfile", "go.mod"),
            ".gradle" to listOf("build.gradle", "build.gradle.kts", "settings.gradle"),
            "Pods" to listOf("Podfile"),
        )

    /** Lock files that are package-manager state and never junk. */
    val PACKAGE_LOCKS =
        listOf(
            "Cargo.lock",
            "yarn.lock",
            "bun.lock",
            "poetry.lock",
            "uv.lock",
            "pdm.lock",
            "Pipfile.lock",
            "Gemfile.lock",
            "composer.lock",
            "flake.lock",
            "Podfile.lock",
            "pubspec.lock",
            "mix.lock",
            "deno.lock",
        )

    const val NO_MANIFEST = "no lockfile or manifest beside it: cannot be reinstalled"
    const val HELD = "held by a running process"
    const val OWNER_UNKNOWN = "owner unknown"
}

/** `deps` has no D36 evidence, so it is listed only; one nothing beside it could reinstall says that first. */
private fun depsReason(path: String): String {
    val name = fileName(path).orEmpty()
    val manifests =
        JunkKinds.DEPS
            .firstOrNull { it.first == name }
            ?.second
            .orEmpty()
    val beside = parent(path) ?: path
    return if (manifests.any { isFile("$beside/$it") }) JunkKinds.NOT_DOCUMENTED else JunkKinds.NO_MANIFEST
}

private fun enters(
    name: String,
    path: String,
    skip: (String) -> Boolean,
) = name !in JunkKinds.SKIP_DIRS && !skip(path)

/** Depth-first search under [root] for what [pick] names; a picked entry and a symlink are never entered. */
private fun find(
    root: String,
    deadline: TimeSource.Monotonic.ValueTimeMark,
    skip: (String) -> Boolean,
    pick: (String, EntryKind) -> String?,
): List<Pair<String, String>> {
    val found = ArrayList<Pair<String, String>>()
    val stack = ArrayDeque(listOf(root to 0))
    while (stack.isNotEmpty()) {
        val (dir, depth) = stack.removeLast()
        if (deadline.hasPassedNow()) break
        for (name in listDirOrEmpty(dir)) {
            val path = "$dir/$name"
            val kind = entryKind(path) ?: continue
            val k = pick(name, kind)
            if (k != null) {
                found += path to k
            } else if (kind == EntryKind.Dir && depth < JunkKinds.MAX_DEPTH && enters(name, path, skip)) {
                stack.addLast(path to depth + 1)
            }
        }
    }
    return found
}

/**
 * Lock, swap and backup files and the file-based `index` leftovers under an agent's folders. None has D36 evidence,
 * so each is kept: a live owner or a held lock says so, the rest read "not documented". Package lockfiles are not listed.
 */
fun foundItems(
    roots: List<String>,
    limit: Duration,
): List<JunkItem> {
    val deadline = TimeSource.Monotonic.markNow() + limit
    return roots.flatMap { find(it, deadline, ::isBackupDir, ::lockOrSwap) }.map { (path, kind) ->
        val reason =
            when {
                kind == "swap" -> swapReason(path)
                kind == "locks" && entryKind(path) == EntryKind.File -> heldReason(path)
                else -> null
            }
        val cls = if (kind == "index" || kind == "backups") "review" else "safe"
        junkItem(kind, path, JunkKinds.NO_EVIDENCE, reason ?: JunkKinds.NOT_DOCUMENTED, limit).copy(cls = cls)
    }
}

/** The entries of the §22 temp dirs [dirs], kept while touched within [minAge] (`[agents.junk] temp_min_age_hours`). */
fun tempItems(
    dirs: List<String>,
    minAge: Duration,
    cx: JunkCtx,
    limit: Duration,
): List<JunkItem> = agedItems("temp", dirs, JunkCache.SECTION_22, minAge, cx, limit)

/**
 * One item per entry of each folder in [dirs], or the path itself when it is a file: kept while touched within
 * [minAge] or, for a file, while another process holds a lock on it. A symlink is never an item.
 */
fun agedItems(
    kind: String,
    dirs: List<String>,
    evidence: String,
    minAge: Duration,
    cx: JunkCtx,
    limit: Duration,
): List<JunkItem> {
    val policy = Policy(minAge, cx.policy.now)
    val items = ArrayList<JunkItem>()
    for (dir in dirs.filterNot(::isSymlink)) {
        val entries = if (isDir(dir)) listDirOrEmpty(dir).map { "$dir/$it" } else listOf(dir)
        for (path in entries) {
            val meta = runCatching { symlinkMeta(path) }.getOrNull()?.takeIf { it.kind != EntryKind.Symlink } ?: continue
            var cut = false
            val entry =
                if (meta.kind == EntryKind.Dir) {
                    val (u, c) = usageUntil(path, TimeSource.Monotonic.markNow() + limit)
                    cut = c
                    Cache(path, 0, u.modified, u.incomplete)
                } else {
                    Cache(path, 0, meta.mtimeNanos, false)
                }
            val kept =
                (if (cut) JunkCache.SCAN_STOPPED else keptBecause(entry, false, false, policy))
                    ?: if (meta.kind == EntryKind.File) heldReason(path) else null
            items += junkItem(kind, path, evidence, kept, limit)
        }
    }
    return items
}

/**
 * Build output in agent worktrees: a tagged cache (`target/`) is judged by T152's rules, idle and never the worktree
 * the command runs from (T342); `dist/`, `.next/` and `__pycache__/` carry no tag, so they are listed read-only (D36).
 */
fun buildItems(
    worktrees: List<String>,
    cx: JunkCtx,
    limit: Duration,
): List<JunkItem> {
    val items = ArrayList<JunkItem>()
    for (wt in worktrees) {
        val (usage, cut) = usageUntil(wt, TimeSource.Monotonic.markNow() + limit)
        val current = pathStartsWith(cx.cwd, canon(wt))
        for (cache in usage.caches) {
            val kept = if (cut) JunkCache.SCAN_STOPPED else keptBecause(cache, current, false, cx.policy)
            items += junkItem("build", cache.path, JunkCache.TAG, kept, limit)
        }
        val tagged = { p: String -> usage.caches.any { it.path == p } }
        val named = { name: String, kind: EntryKind ->
            when {
                kind != EntryKind.Dir -> null
                name in JunkKinds.BUILD_DIRS -> "build"
                JunkKinds.DEPS.any { it.first == name } -> "deps"
                else -> null
            }
        }
        // A tagged `.venv` (uv writes the tag) is already `build` above.
        for ((path, kind) in find(wt, TimeSource.Monotonic.markNow() + limit, tagged, named).filterNot { tagged(it.first) }) {
            val (cls, kept) = if (kind == "deps") "review" to depsReason(path) else "safe" to JunkKinds.NOT_DOCUMENTED
            items += junkItem(kind, path, JunkKinds.NO_EVIDENCE, kept, limit).copy(cls = cls)
        }
    }
    return items
}

/**
 * Version directories of the rtok plugin that Claude Code unpacked and no longer uses: every one under
 * `<claude>/plugins/cache/rtok/rtok` that `installed_plugins.json` does not name as an `installPath` (T279). A record
 * that is missing, unreadable or names no rtok install says nothing, so then none is listed.
 */
fun stagingCaches(claude: String): List<OwnedCache> {
    val json = runCatching { StrictJson.parse(readToString("$claude/plugins/installed_plugins.json")) }.getOrNull()
    val installs = ((json as? JsonObject)?.get("plugins") as? JsonObject)?.get(Claude.PLUGIN_ID) as? JsonArray
    val used =
        installs.orEmpty().mapNotNull { i ->
            val p = ((i as? JsonObject)?.get("installPath") as? JsonPrimitive)?.takeIf { it.isString }?.content
            p?.let(::canon)
        }
    if (used.isEmpty()) return emptyList()
    val cache = "$claude/${ClaudeUpdate.PLUGIN_CACHE}"
    return listDirOrEmpty(cache)
        .map { "$cache/$it" }
        .filter { isDir(it) && !isSymlink(it) && canon(it) !in used }
        .map { OwnedCache(it, JunkCache.RTOK_OWN, true) }
}

/**
 * The `<commit>` folders of a per-commit cache (VS Code's `CachedData`, §22.2) that are not the newest: the folder
 * touched last stands for the running commit and stays, as does one whose time cannot be read; a link is never
 * followed. T152's idle rule still applies to each one.
 */
fun staleCodeCaches(dir: String): List<OwnedCache> {
    val dirs =
        listDirOrEmpty(dir)
            .map { "$dir/$it" }
            .mapNotNull { p -> runCatching { symlinkMeta(p) }.getOrNull()?.takeIf { it.kind == EntryKind.Dir }?.let { p to it.mtimeNanos } }
    val newest = dirs.maxOfOrNull { it.second } ?: return emptyList()
    return dirs.filter { it.second < newest }.map { OwnedCache(it.first, JunkCache.SECTION_22, true) }
}
