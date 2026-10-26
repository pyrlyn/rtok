// Port of crates/rtok-store/src/agents.rs (T282, D34): rtok's own agent id — a random UUIDv4 per
// host session, shown as its first 8 hex chars and resolved from any unique prefix of 4+ chars.
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package rtok.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.plugin.sdk.debugStr
import humantime.DurationParseException
import humantime.parseHumanDuration
import kotlin.time.Duration
import kotlin.uuid.Uuid

data class AgentRow(
    val id: String, val hostId: Int, val hostSessionId: String, val parentKey: String, val parentId: String?, val cwd: String?,
    val startedAt: Long, val lastSeen: Long, val endedAt: Long?, val activity: String?,
    /** T283.3: the hook process's ancestors, nearest first. */
    val ancestors: List<Int>,
)

private const val AGENT_COLS = "id, host_id, host_session_id, parent_key, parent_id, cwd, started_at, last_seen, ended_at, activity, ancestors"
private fun agentRow(r: Row) = AgentRow(r.string(0), r.int(1), r.string(2), r.string(3), r.stringOrNull(4), r.stringOrNull(5), r.long(6), r.long(7),
    r.longOrNull(8), r.stringOrNull(9), r.stringOrNull(10)?.split(' ', '\t', '\n', '\r')?.mapNotNull { it.toIntOrNull() } ?: emptyList())

/** One agent joined to its host's slug — what `rtok agents whoami` prints and serializes for `--json`. */
@Serializable
data class AgentDetail(
    val id: String, val short: String, val host: String,
    @SerialName("host_session_id") val hostSessionId: String,
    @SerialName("parent_id") val parentId: String?, val cwd: String?,
    @SerialName("started_at") val startedAt: Long, @SerialName("last_seen") val lastSeen: Long,
    @SerialName("ended_at") val endedAt: Long?, val activity: String?,
    @SerialName("status_text") val statusText: String?,
)

private const val DETAIL_SQL = "SELECT agents.id, hosts.slug, agents.host_session_id, agents.parent_id, agents.cwd, agents.started_at, agents.last_seen, " +
    "agents.ended_at, agents.activity, agents.status_text FROM agents INNER JOIN hosts ON hosts.id = agents.host_id"
private fun detail(r: Row): AgentDetail { val id = r.string(0)
    return AgentDetail(id, id.take(8), r.string(1), r.string(2), r.stringOrNull(3), r.stringOrNull(4), r.long(5), r.long(6), r.longOrNull(7), r.stringOrNull(8), r.stringOrNull(9)) }

/** humantime window shared by `[agents] idle` and the worktree `--idle` / `--stale-lock` flags; `source` names the flag. */
fun parseIdleFrom(idle: String, source: String): Duration = try { parseHumanDuration(idle) } catch (e: DurationParseException) {
    throw StoreException("bad $source ${debugStr(idle)}: ${e.message}")
}

/** `[agents] idle` in whole seconds. */
fun idleSecs(idle: String): Long = parseIdleFrom(idle, "[agents] idle").inWholeSeconds

private fun mainAgentId(db: Db, hostId: Int, session: String) =
    db.stringOf("SELECT id FROM agents WHERE host_id = ? AND host_session_id = ? AND parent_key = '' LIMIT 1", hostId, session)

/** T929: a sub-agent has registered in this host session. */
fun Store.sessionHasSubagent(session: String): Boolean = conn {
    it.longOf("SELECT EXISTS (SELECT 1 FROM agents WHERE host_session_id = ? AND parent_key <> '')", session) == 1L
}

/**
 * Ensure the row for this host session (or its sub-agent `parentKey`) exists and is fresh; its rtok id.
 * A repeat clears `ended_at` (T324); `activity = null` keeps what is stored; the main row adopts its
 * session's unparented sub-agents and a sub-agent's repeat fills a missing parent (T613).
 */
fun Store.registerAgent(hostId: Int, hostSession: String, parentKey: String?, cwd: String?, activity: String?): String = conn { db ->
    val key = parentKey ?: ""
    db.transaction("IMMEDIATE") {
        val parentId = if (key.isEmpty()) null else mainAgentId(db, hostId, hostSession)
        val rowId = db.queryOne("INSERT INTO agents (id, host_id, host_session_id, parent_key, parent_id, cwd, activity) VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (host_id, host_session_id, parent_key) DO UPDATE SET last_seen = unixepoch(), ended_at = NULL, " +
            "parent_id = COALESCE(agents.parent_id, excluded.parent_id), cwd = COALESCE(excluded.cwd, agents.cwd), " +
            "activity = COALESCE(excluded.activity, agents.activity) RETURNING id",
            Uuid.random().toString(), hostId, hostSession, key, parentId, cwd, activity) { it.string(0) }!!
        if (key.isEmpty()) db.execute("UPDATE agents SET parent_id = ? WHERE host_id = ? AND host_session_id = ? AND parent_key <> '' AND parent_id IS NULL", rowId, hostId, hostSession)
        rowId
    }
}

/** The main row of one host session, without touching it (T473). */
fun Store.mainAgent(hostId: Int, hostSession: String): String? = conn { mainAgentId(it, hostId, hostSession) }

/** `last_seen` = now and `activity` exactly as passed (null clears it). */
fun Store.touchAgent(id: String, activity: String?) = conn { it.execute("UPDATE agents SET last_seen = unixepoch(), activity = ? WHERE id = ?", activity, id); Unit }

fun Store.endAgent(id: String, ts: Long) = conn { it.execute("UPDATE agents SET ended_at = ? WHERE id = ?", ts, id); Unit }

/** The one id starting with `prefix`; "unknown" / "ambiguous: <ids>" otherwise. D34: at least 4 chars. */
fun Store.resolveAgent(prefix: String): String {
    if (prefix.codePointCount() < 4) throw StoreException("an agent id prefix needs at least 4 characters")
    val len = prefix.encodeToByteArray().size
    val matches = conn { it.query("SELECT id FROM agents WHERE substr(id, 1, ?) = ?", len, prefix) { r -> r.string(0) } }
    return when (matches.size) { 0 -> throw StoreException("unknown"); 1 -> matches[0]; else -> throw StoreException("ambiguous: ${matches.joinToString(", ")}") }
}

private fun String.codePointCount(): Int { var n = 0; var i = 0; while (i < length) { if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) i++; i++; n++ }; return n }

fun Store.agentDetail(id: String): AgentDetail? = conn { it.queryOne("$DETAIL_SQL WHERE agents.id = ? LIMIT 1", id, map = ::detail) }

/** Every agent of these host sessions, oldest first (`rtok agents sessions`, T284). */
fun Store.agentsOfSessions(sessions: List<String>): List<AgentDetail> = if (sessions.isEmpty()) emptyList() else conn {
    it.query("$DETAIL_SQL WHERE agents.host_session_id IN (${inList(sessions.size)}) ORDER BY agents.started_at, agents.id", *sessions.toTypedArray(), map = ::detail)
}

/** Sub-agents whose `parent_id` is `id`, oldest first. */
fun Store.agentChildren(id: String): List<AgentDetail> = conn { it.query("$DETAIL_SQL WHERE agents.parent_id = ? ORDER BY agents.started_at, agents.id", id, map = ::detail) }

/** Set or clear the agent's own status text (T284); `false` when no row has this id. */
fun Store.setAgentStatus(id: String, text: String?): Boolean = conn { it.execute("UPDATE agents SET status_text = ? WHERE id = ?", text, id) > 0 }

/** Agents not ended and seen within `idle` of SQLite's own clock. */
fun Store.liveAgents(idle: String): List<AgentRow> {
    val secs = idleSecs(idle)
    return conn { it.query("SELECT $AGENT_COLS FROM agents WHERE ended_at IS NULL AND last_seen >= unixepoch() - ?", secs, map = ::agentRow) }
}

/** T949: agents with an `ended_at`. */
fun Store.endedAgentIds(): List<String> = conn { it.query("SELECT id FROM agents WHERE ended_at IS NOT NULL") { r -> r.string(0) } }

/** T283.3: record the pids above the registering hook (nearest first); written only when different, an empty chain keeps what is stored. */
fun Store.setAgentAncestors(id: String, chain: List<Int>) {
    if (chain.isEmpty()) return
    val text = chain.joinToString(" ")
    conn { it.execute("UPDATE agents SET ancestors = ? WHERE id = ? AND (ancestors IS NULL OR ancestors <> ?)", text, id, text) }
}

fun Store.agentLastSeen(id: String): Long? = conn { it.longOf("SELECT last_seen FROM agents WHERE id = ?", id) }

/** Test fixture: place `last_seen` at an exact time. */
fun Store.setAgentLastSeen(id: String, ts: Long) = conn { it.execute("UPDATE agents SET last_seen = ? WHERE id = ?", ts, id); Unit }

fun Store.agentRow(id: String): AgentRow? = conn { it.queryOne("SELECT $AGENT_COLS FROM agents WHERE id = ?", id, map = ::agentRow) }
