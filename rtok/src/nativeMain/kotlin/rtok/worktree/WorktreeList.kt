// Port of src/worktree/list.rs (T151, T154, T285, T936): `rtok worktree list` rows — what every worktree costs on
// disk, who is bound to it, and the orphans git cannot see. Read-only; the walk itself is in CacheUsage.kt.
package rtok.worktree

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.Col
import rtok.agents.SessionsTable
import rtok.store.SessionSeen
import rtok.store.Store
import rtok.store.agentDetail
import rtok.store.liveAgents
import rtok.store.openWorktreeClaims
import rtok.store.sessionsByCwd
import rtok.sys.ChangePreview.humanBytes
import rtok.sys.canon
import rtok.sys.components
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.pathStartsWith
import rtok.sys.readDir
import rtok.sys.readToString
import rtok.sys.samePath
import rtok.table

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val ID8 = 8
private const val LEFT_COLS = 9

/** T285: a bound agent as the store knows it; `host` null and `state` `unknown` when the store has no such agent. */
@Serializable
data class Bound(
    val id: String,
    val host: String? = null,
    /** `live`, `idle`, `ended` or `unknown`. */
    val state: String = "unknown",
)

/** T154: the newest session the hooks saw working in a worktree, from `sessions.cwd`; the lock reason still wins. */
@Serializable
data class Seen(
    val session: String,
    val host: String?,
    @SerialName("seen_unix") val seenUnix: Long,
    /** No `SessionEnd` recorded — the host may simply never send one. */
    val live: Boolean,
)

@Serializable
data class Row(
    val path: String,
    val branch: String? = null,
    /** From the lock reason; null with [locked] means the owner is unknown. */
    val owner: String? = null,
    val locked: Boolean = false,
    /** T150's state, or `orphan`. */
    val state: String,
    /** Which tool made the worktree (T289): `main`, a host's pool, or `other`. */
    val origin: String,
    /** The rtok agent (T282) the worktree is bound to: the lock's, else a claim row (T285). */
    var agent: Bound? = null,
    /** The newest session the hooks saw working here (T154); never set on the main checkout. */
    var session: Seen? = null,
    @SerialName("source_bytes") val sourceBytes: Long,
    /** Null when the build caches were not walked (T936); see [WorktreeList.rowsSized]. */
    @SerialName("cache_bytes") val cacheBytes: Long?,
    @SerialName("modified_unix") val modifiedUnix: Long?,
) {
    companion object {
        /** `Row::new`: the walk of [path], sized or sources only. */
        fun of(
            path: String,
            state: String,
            sized: Boolean,
        ): Row {
            val used = if (sized) usage(path) else usageSources(path)
            return Row(
                path = path,
                state = state,
                origin = if (state == "main") "main" else Origin.of(path),
                sourceBytes = used.source,
                cacheBytes = if (sized) used.cache else null,
                modifiedUnix = used.modified?.takeIf { it >= 0 }?.div(NANOS_PER_SECOND),
            )
        }
    }
}

object WorktreeList {
    /**
     * Directories that look like a linked worktree but that git does not list: their `.git` file names a
     * `…/worktrees/<id>` admin directory that no longer exists. Looked for next to the known worktrees and in
     * `<main>/.claude/worktrees`; [listed] is every worktree git lists, the main checkout first.
     */
    fun orphans(listed: List<String>): List<String> {
        val known = listed.map(::canon).toSet()
        val parents = listed.drop(1).mapNotNull { p -> parent(p)?.let(::canon) }.toMutableSet()
        listed.firstOrNull()?.let { parents += canon(join(it, ".claude/worktrees")) }
        return parents
            .sorted()
            .flatMap { p -> runCatching { readDir(p) }.getOrDefault(emptyList()).map { join(p, it) } }
            .map(::canon)
            .filter { it !in known && isOrphan(it) }
    }

    @Suppress("ReturnCount")
    private fun isOrphan(dir: String): Boolean {
        // A `.git` directory (a repository of its own) fails to read as a file.
        val dotGit = runCatching { readToString(join(dir, ".git")) }.getOrNull() ?: return false
        val rel = dotGit.trim().takeIf { it.startsWith("gitdir: ") }?.removePrefix("gitdir: ") ?: return false
        // A relative `gitdir:` (`worktree.useRelativePaths`) resolves against the worktree.
        val admin = join(dir, rel)
        val parts = components(admin)
        return parts.size >= 2 && parts[parts.size - 2] == "worktrees" && !exists(admin)
    }

    /**
     * `bind`: fill [Row.agent] from the store — an unlocked worktree's open claim row, then every bound agent's host
     * and state: `live` within `[agents] idle`, else `ended` or `idle`.
     */
    fun bind(
        rows: List<Row>,
        store: Store,
        idle: String,
    ) {
        val claims = store.openWorktreeClaims()
        val live = store.liveAgents(idle).map { it.id }.toSet()
        for (row in rows.filter { it.state != "main" }) {
            if (row.agent == null && !row.locked) {
                val dir = canon(row.path)
                row.agent = claims.firstOrNull { (p, _) -> samePath(canon(p), dir) }?.let { Bound(it.second) }
            }
            val bound = row.agent
            val detail = bound?.let { store.agentDetail(it.id) }
            if (bound != null && detail != null) {
                val state =
                    when {
                        bound.id in live -> "live"
                        detail.endedAt != null -> "ended"
                        else -> "idle"
                    }
                row.agent = bound.copy(host = detail.host, state = state)
            }
        }
    }

    /**
     * `attribute`: for each linked worktree the newest session whose `cwd` is the worktree or under it (both
     * canonicalised, so `/tmp` and `/private/tmp` agree). The main checkout belongs to nobody.
     */
    fun attribute(
        rows: List<Row>,
        sessions: List<SessionSeen>,
    ) {
        val seen = sessions.map { canon(it.cwd) to it }
        val newest = compareBy<SessionSeen> { it.lastSeen }.thenByDescending { it.id }
        for (row in rows.filter { it.state != "main" }) {
            val dir = canon(row.path)
            row.session =
                seen
                    .filter { (cwd, _) -> pathStartsWith(cwd, dir) }
                    .map { it.second }
                    .maxWithOrNull(newest)
                    ?.let { Seen(it.id, it.host, it.lastSeen, it.endedAt == null) }
        }
    }

    /** Every worktree of the repository [cwd] belongs to, then the orphans; the build caches are not walked (T936). */
    fun rows(cwd: String): List<Row> = rowsWith(cwd, false)

    /** [rows] with every tagged build cache walked and its bytes filled in. */
    fun rowsSized(cwd: String): List<Row> = rowsWith(cwd, true)

    private fun rowsWith(
        cwd: String,
        sized: Boolean,
    ): List<Row> {
        val listed =
            Worktrees.inventoryWith(cwd) { e ->
                val owner = e.record.owner()
                Row.of(e.record.path, e.state.label, sized).copy(
                    branch = e.record.branch,
                    agent = owner?.agent?.let(::Bound),
                    owner = owner?.owner,
                    locked = e.record.locked != null,
                )
            }
        val rows = listed.map { it.second }.toMutableList()
        val orphans = orphans(listed.map { it.first.record.path })
        rows += Worktrees.parMap(orphans) { _, p -> Row.of(p, "orphan", sized) }
        return rows
    }

    /**
     * `attribute_with_store`: T154's inferred session, then T285's bound agent. Without a store (or on a store error)
     * the rows stay unattributed. The caller scans first and opens the store after.
     */
    fun attributeWithStore(
        rows: List<Row>,
        store: Store?,
        idle: String,
    ) {
        if (store == null) return
        val seen = runCatching { store.sessionsByCwd() }.getOrNull() ?: return
        attribute(rows, seen)
        runCatching { bind(rows, store, idle) }
    }

    /** `to_table`; [now] is seconds since the epoch. */
    fun toTable(
        rows: List<Row>,
        now: Long,
    ): String {
        val dash = "-"
        val header = listOf("path", "branch", "owner", "agent", "agent state", "state", "origin", "seen", "modified", "source", "cache")
        val lines = listOf(header) + rows.map { r -> cells(r, now, dash) }
        val cols = List(LEFT_COLS) { Col.left(0) } + listOf(Col.right(0), Col.right(0))
        val source = rows.sumOf { it.sourceBytes }
        val cache =
            if (rows.all { it.cacheBytes != null }) {
                "${humanBytes(rows.sumOf { it.cacheBytes ?: 0 })} build cache"
            } else {
                "build cache not sized (--sizes)"
            }
        return table(cols, lines) + "\n" +
            "${rows.size} worktrees: ${humanBytes(source)} source, $cache (logical bytes; clones and hard links count in full)\n"
    }

    private fun cells(
        r: Row,
        now: Long,
        dash: String,
    ): List<String> {
        val owner = r.owner ?: if (r.locked) "locked, owner unknown" else dash
        val id8 = { id: String -> id.take(ID8) }
        val host = { h: String? -> h ?: "?" }
        val a = r.agent
        val s = r.session
        val (agent, agentState) =
            when {
                a != null -> "${id8(a.id)} ${host(a.host)}" to a.state
                s != null -> "seen ${host(s.host)} ${id8(s.session)}" to dash
                else -> dash to dash
            }
        val ago = { t: Long -> SessionsTable.duration(now - t) }
        return listOf(
            r.path,
            r.branch ?: dash,
            owner,
            agent,
            agentState,
            r.state,
            r.origin,
            s?.let { ago(it.seenUnix) } ?: dash,
            r.modifiedUnix?.let(ago) ?: dash,
            humanBytes(r.sourceBytes),
            r.cacheBytes?.let(::humanBytes) ?: dash,
        )
    }
}
