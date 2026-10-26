// Port of src/model/agents.rs (T284): who is running and what each agent is doing — the one model behind
// `rtok agents sessions` / `show` / `status` and their MCP tools.
package rtok.model

import rtok.agents.AgentState
import rtok.agents.AgentView
import rtok.agents.SessionView
import rtok.config.ProjectRoot
import rtok.store.AgentDetail
import rtok.store.SessionTotals
import rtok.store.Store
import rtok.store.StoreException
import rtok.store.agentChildren
import rtok.store.agentDetail
import rtok.store.agentsOfSessions
import rtok.store.idleSecs
import rtok.store.openWorktreeClaims
import rtok.store.recentSessionTotals
import rtok.store.resolveAgent
import rtok.store.setAgentStatus
import rtok.store.unreadCounts

object Agents {
    /** Rows of the Sessions page (model/mod.rs `SESSIONS_ROWS`). */
    const val SESSIONS_ROWS = 120L

    /** Longest status text an agent may set, in chars (after cleaning). */
    const val STATUS_MAX = 120

    /** `<checkout dir>[/<path inside it>]` for [cwd]; [cwd] itself outside any checkout. */
    fun worktreeLabel(cwd: String): String {
        val root = ProjectRoot.find(cwd)?.checkout?.trimEnd('/') ?: return cwd
        val name = rtok.sys.fileName(root) ?: root
        val rel = if (cwd.startsWith("$root/")) cwd.removePrefix("$root/").trimEnd('/') else ""
        return if (rel.isEmpty()) name else "$name/$rel"
    }

    /** Nest [rows] under their parents: a row whose parent is absent is a root; ended ones dropped unless [all]. */
    internal fun nest(
        rows: List<AgentDetail>,
        now: Long,
        idle: Long,
        all: Boolean,
    ): List<AgentView> =
        rows
            .filter { r -> r.parentId.let { p -> p == null || rows.none { it.id == p } } }
            .mapNotNull { tree(it, rows, now, idle, all) }

    private fun tree(
        r: AgentDetail,
        rows: List<AgentDetail>,
        now: Long,
        idle: Long,
        all: Boolean,
    ): AgentView? {
        val state = AgentState.of(r.endedAt, r.lastSeen, now, idle)
        if (!all && state == AgentState.Ended) return null
        val subs = rows.filter { it.parentId == r.id }.mapNotNull { tree(it, rows, now, idle, all) }
        return AgentView(r, null, r.cwd?.let(::worktreeLabel), emptyList(), 0, state, subs)
    }

    /** Pair each session with its main agent (sub-agents nested); [all] keeps ended rows. */
    fun sessionViews(
        sessions: List<SessionTotals>,
        agents: List<AgentDetail>,
        now: Long,
        idle: Long,
        all: Boolean,
    ): List<SessionView> {
        val trees = nest(agents, now, idle, true)
        return sessions.mapNotNull { s ->
            val agent =
                trees
                    .firstOrNull { a -> a.detail.hostSessionId == s.id && (s.host == null || s.host == a.detail.host) }
                    ?.let { a -> a.copy(model = s.model, subAgents = a.subAgents.filter { all || it.state != AgentState.Ended }) }
            val state =
                when {
                    s.endedAt != null -> AgentState.Ended
                    agent != null -> agent.state
                    else -> AgentState.of(null, s.lastActivity, now, idle)
                }
            if (all || state != AgentState.Ended) SessionView(s, state, agent) else null
        }
    }

    /** `rtok agents sessions`: the Sessions page's rows, each with its agent tree. */
    fun agentSessions(
        store: Store,
        idleWindow: String,
        all: Boolean,
        now: Long,
    ): List<SessionView> {
        val idle = idleSecs(idleWindow)
        val sessions = runCatching { store.recentSessionTotals(0, SESSIONS_ROWS) }.getOrDefault(emptyList())
        val agents = store.agentsOfSessions(sessions.map { it.id })
        val views = sessionViews(sessions, agents, now, idle, all)
        val fill = Enrich(store)
        return views.map { v -> v.copy(agent = v.agent?.let(fill::apply)) }
    }

    /** The open worktree claims (T285) and unread counts (T287); a claimed worktree's name replaces the cwd label. */
    private class Enrich(
        store: Store,
    ) {
        private val claims = store.openWorktreeClaims()
        private val unread = store.unreadCounts()

        fun apply(v: AgentView): AgentView {
            val worktrees = claims.filter { it.second == v.detail.id }.map { it.first }
            val named = worktrees.firstOrNull()?.let { rtok.sys.fileName(it) }
            return v.copy(
                worktrees = worktrees,
                worktree = named ?: v.worktree,
                unread = unread[v.detail.id] ?: 0,
                subAgents = v.subAgents.map(::apply),
            )
        }
    }

    private fun find(
        views: List<AgentView>,
        id: String,
    ): AgentView? = views.firstNotNullOfOrNull { v -> if (v.detail.id == id) v else find(v.subAgents, id) }

    /** `rtok agents show <prefix>`: the agent as [agentSessions] (all) shows it, else built from its own row. */
    fun agentShow(
        store: Store,
        idleWindow: String,
        prefix: String,
        now: Long,
    ): AgentView {
        val unknown = "no agent id starts with ${rtok.plugin.sdk.debugStr(prefix)}"
        val id =
            runCatching { store.resolveAgent(prefix) }.getOrElse { e ->
                throw StoreException(if (e.message == "unknown") unknown else "agent id ${rtok.plugin.sdk.debugStr(prefix)} is ${e.message}", e)
            }
        val trees = agentSessions(store, idleWindow, true, now).mapNotNull { it.agent }
        return find(trees, id) ?: built(store, idleWindow, id, now) ?: throw StoreException(unknown)
    }

    /** An agent past the Sessions page's window, built from its own row and sub-agents. */
    private fun built(
        store: Store,
        idleWindow: String,
        id: String,
        now: Long,
    ): AgentView? {
        val me = store.agentDetail(id) ?: return null
        val fill = Enrich(store)
        return find(nest(listOf(me) + store.agentChildren(id), now, idleSecs(idleWindow), true).map(fill::apply), id)
    }

    /** Status as stored: control chars stripped, trimmed; null clears it. Longer than [STATUS_MAX] is refused. */
    fun cleanStatus(text: String): String? {
        val clean = text.filter { !it.isISOControl() }.trim()
        val n = clean.codePointCount()
        require(n <= STATUS_MAX) { "status text is $n chars; at most $STATUS_MAX allowed" }
        return clean.ifEmpty { null }
    }

    /** `rtok agents status <text>`: the caller's own status text. */
    fun setStatus(
        store: Store,
        agentId: String?,
        text: String,
    ): String? {
        val id = agentId?.takeIf { it.isNotEmpty() }?.let { runCatching { store.resolveAgent(it) }.getOrNull() }
        requireNotNull(id) { "not inside an agent session" }
        val clean = cleanStatus(text)
        store.setAgentStatus(id, clean)
        return clean
    }

    private fun String.codePointCount(): Int = count { !it.isLowSurrogate() }
}
