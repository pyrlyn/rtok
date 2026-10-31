// Port of src/agents/link.rs + shell_agent.rs: which registered agent session an `rtok mcp` process (or an
// `rtok` command in an agent's shell) acts for.
package rtok.agents

import rtok.store.AgentRow
import rtok.store.Store
import rtok.store.hostId
import rtok.store.liveAgents
import rtok.store.mainAgent
import rtok.store.registerAgent

/** How a link was made. `Cwd` and `Ancestor` are guesses that a later session can change. */
enum class Rule(
    val label: String,
) {
    Env("env"),
    Ancestor("ancestor"),
    Cwd("cwd"),
    Arg("arg"),
    Own("own"),
    Hook("hook"),
    ;

    val isStable get() = this != Cwd && this != Ancestor
}

sealed interface Link {
    data class Linked(
        val id: String,
        val rule: Rule,
    ) : Link

    data class Ambiguous(
        val ids: List<String>,
    ) : Link

    /** Under no live session's process tree although some register one: a shared server. */
    data object Outside : Link

    data object None : Link
}

/** What the caller knows about itself. */
class Caller(
    val host: String,
    val hostId: Int,
    val cwd: String?,
    val ownSession: String,
    val idle: String,
    val since: Long,
    val ancestors: List<Int>,
)

/** Whether a host has no hook events at all, so its MCP process registers its own row (agents registry). */
fun interface HookSupport {
    fun hookless(host: String): Boolean

    companion object {
        /** Every host counts as hooked (tests); production uses [HostHookSupport]. */
        val Unknown = HookSupport { false }
    }
}

object AgentLink {
    /** Process ancestors stored on an agent row and compared here. */
    const val ANCESTORS = 3

    /** `(host, env var, registers)`: the host session id an agent exports to its tools. */
    val SESSION_ENV = listOf(Triple("grok", "GROK_SESSION_ID", true), Triple("claude", "CLAUDE_CODE_SESSION_ID", false))

    fun resolve(
        store: Store,
        who: Caller,
        hooks: HookSupport = HostHookSupport,
        env: (String) -> String?,
    ): Link {
        byEnv(store, who, env)?.let { return it }
        val live = store.liveAgents(who.idle).filter { it.hostId == who.hostId && it.parentKey.isEmpty() && it.lastSeen >= who.since }
        val chained = who.ancestors.isNotEmpty() && live.any { it.ancestors.isNotEmpty() }
        return byAncestor(live, who)
            ?: byCwd(live, who)
            ?: if (hooks.hookless(who.host)) {
                Link.Linked(store.registerAgent(who.hostId, who.ownSession, null, who.cwd, "mcp"), Rule.Own)
            } else if (chained) {
                Link.Outside
            } else {
                Link.None
            }
    }

    private fun byEnv(
        store: Store,
        who: Caller,
        env: (String) -> String?,
    ): Link? {
        val entry = SESSION_ENV.firstOrNull { it.first == who.host }
        val session = entry?.let { env(it.second) }?.trim()?.takeIf { it.isNotEmpty() }
        val id =
            when {
                entry == null || session == null -> null
                entry.third -> store.registerAgent(who.hostId, session, null, who.cwd, null)
                else -> store.mainAgent(who.hostId, session)
            }
        return id?.let { Link.Linked(it, Rule.Env) }
    }

    private fun byAncestor(
        live: List<AgentRow>,
        who: Caller,
    ): Link? {
        fun depth(a: AgentRow) = who.ancestors.indexOfFirst { it in a.ancestors }.takeIf { it >= 0 }
        val best = live.mapNotNull(::depth).minOrNull() ?: return null
        return pick(live.filter { depth(it) == best }.map { it.id }, Rule.Ancestor)
    }

    /**
     * Past [byAncestor] no live chain overlaps ours, which proves such an agent is not our caller: the cwd of a server
     * an app shares is whichever client's root came first, and matching it would answer for that client (or a row
     * without a chain, or a caller without one, can only be judged by its cwd).
     */
    private fun byCwd(
        live: List<AgentRow>,
        who: Caller,
    ): Link? {
        val cwd = who.cwd ?: return null
        val here = rtok.sys.canonicalize(cwd) ?: cwd
        val open = live.filter { who.ancestors.isEmpty() || it.ancestors.isEmpty() }
        val ids = open.filter { a -> a.cwd?.let { (rtok.sys.canonicalize(it) ?: it) == here } == true }.map { it.id }
        return if (ids.isEmpty()) null else pick(ids, Rule.Cwd)
    }

    private fun pick(
        ids: List<String>,
        rule: Rule,
    ): Link = if (ids.size == 1) Link.Linked(ids[0], rule) else Link.Ambiguous(ids.sorted())

    /**
     * T473: the agent an `rtok` command run from an agent's shell acts for: `RTOK_AGENT_ID`, else the main agent
     * of the host session a [SESSION_ENV] var names. Never registers a row.
     */
    fun shellAgent(
        store: Store?,
        env: (String) -> String?,
    ): String? {
        fun set(k: String) = env(k)?.takeIf { it.isNotBlank() }
        return set("RTOK_AGENT_ID") ?: store?.let { s -> SESSION_ENV.firstNotNullOfOrNull { (host, variable, _) -> set(variable)?.let { mainOf(s, host, it.trim()) } } }
    }

    /** A host the `hosts` table does not know registers under `other`, as hooks do. */
    private fun mainOf(
        store: Store,
        host: String,
        session: String,
    ): String? =
        runCatching {
            (store.hostId(host) ?: store.hostId("other"))?.let { store.mainAgent(it, session) }
        }.getOrNull()
}
