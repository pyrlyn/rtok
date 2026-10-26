// The agent side of mcp.rs `Server`: the cached agent link (T283.1), the caller's root (T923), `agent_for`
// (T924) and `whoami`.
package rtok.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.agents.AgentLink
import rtok.agents.Caller
import rtok.agents.HookSupport
import rtok.agents.Link
import rtok.agents.Rule
import rtok.plugin.Runtime
import rtok.store.AgentDetail
import rtok.store.agentDetail
import rtok.store.hostId
import rtok.store.openWorktreeClaims
import rtok.store.resolveAgent
import rtok.store.shortAgentId
import rtok.sys.Mutex

class AgentSession(
    private val cx: Runtime,
    private val hooks: HookSupport = HookSupport.Unknown,
    private val env: (String) -> String? = { rtok.config.envVar(it) },
) {
    /** Unix seconds this process started: a cwd candidate must have been seen since. */
    private val started = rtok.log.now()
    private val lock = Mutex()
    private var agent: Pair<String, Rule>? = null
    private var caller: Pair<Long, String?>? = null

    /** The cached stable link, else one attempt at the link rules; a store error reads as not linked. */
    fun link(): Link {
        val cached = lock.hold { agent }
        val hostId = cx.hostId?.takeIf { cx.config.agents.enabled }
        return when {
            cached != null -> Link.Linked(cached.first, cached.second)
            hostId == null -> Link.None
            else -> resolveFresh(hostId)
        }
    }

    private fun resolveFresh(hostId: Int): Link {
        val who =
            Caller(
                cx.config.hook.host,
                hostId,
                runCatching { rtok.sys.cwd() }.getOrNull(),
                cx.session,
                cx.config.agents.idle,
                started,
                rtok.sys.processAncestors(rtok.sys.pid(), AgentLink.ANCESTORS),
            )
        val found =
            runCatching { AgentLink.resolve(cx.store, who, hooks, env) }.getOrElse {
                cx.log("warn", "mcp", "link", "agent link failed: ${it.message}")
                Link.None
            }
        if (found is Link.Linked && found.rule.isStable) lock.hold { agent = found.id to found.rule }
        return found
    }

    /** The linked agent's registered cwd, re-read at most every [CALLER_TTL] seconds. */
    fun callerDir(): String? {
        val now = rtok.log.now()
        lock.hold { caller }?.let { (at, dir) -> if (now - at < CALLER_TTL) return dir }
        val dir = (link() as? Link.Linked)?.let { runCatching { cx.store.agentDetail(it.id) }.getOrNull()?.cwd }
        lock.hold { caller = now to dir }
        return dir
    }

    /** The agent this session is linked to, or the reason it is not. */
    fun agent(): Pair<AgentDetail, Rule> {
        val host = cx.config.hook.host
        val (id, rule) =
            when (val l = link()) {
                is Link.Linked -> {
                    l.id to l.rule
                }

                is Link.Ambiguous -> {
                    fail("ambiguous: agents ${l.ids.joinToString(", ") { it.take(SHORT) }} share this cwd; pass `agent` with the id of yours (whoami)")
                }

                Link.Outside -> {
                    fail(
                        "not linked to an agent session: this rtok mcp process is under no live $host session and in none's cwd, so one app " +
                            "likely shares it across sessions (Claude desktop's claude_desktop_config.json entry); use `rtok agents` and " +
                            "`rtok worktree` from the agent's shell, or pass `agent` (your id, see agents_list)",
                    )
                }

                Link.None -> {
                    if (!cx.config.agents.enabled) {
                        fail("not linked to an agent session: the agent registry is off ([agents] enabled = false)")
                    } else {
                        fail(
                            "not linked to an agent session: no live $host agent has run a hook in this directory since this server " +
                                "started; retry after your next tool call, check the hooks with `rtok doctor`, or pass `agent` (your id, see agents_list)",
                        )
                    }
                }
            }
        val d = cx.store.agentDetail(id) ?: fail("not linked to an agent session")
        return d to rule
    }

    /** [agent], or the live agent of this host the call names in `agent`; a stable own link is never overridden. */
    fun agentFor(args: JsonObject): Pair<AgentDetail, Rule> {
        val want = Protocol.str(args, "agent")?.takeIf { it.isNotEmpty() } ?: return agent()
        val id = runCatching { cx.store.resolveAgent(want) }.getOrElse { fail("agent $want: ${it.message}") }
        val mine = link()
        if (mine is Link.Linked && mine.rule.isStable && mine.id != id) fail("this session is agent ${shortAgentId(mine.id)}; `agent` $want is another session's")
        val d = cx.store.agentDetail(id) ?: fail("agent $want: unknown")
        val sameHost = cx.store.hostId(d.host) == cx.hostId
        if (!sameHost || d.endedAt != null || d.parentId != null) fail("agent $want is not a live session of ${cx.config.hook.host}")
        return d to Rule.Arg
    }

    fun whoami(args: JsonObject): String {
        val (d, rule) = agentFor(args)
        val worktrees =
            cx.store
                .openWorktreeClaims()
                .filter { it.second == d.id }
                .map { it.first }
        return buildJsonObject {
            put("id", d.id)
            put("short", d.short)
            put("host", d.host)
            put("host_session", d.hostSessionId)
            put("cwd", d.cwd?.let(::JsonPrimitive) ?: JsonNull)
            put("rule", rule.label)
            put("worktrees", JsonArray(worktrees.map(::JsonPrimitive)))
        }.toString()
    }

    private fun fail(msg: String): Nothing = throw IllegalStateException(msg)

    private companion object {
        const val CALLER_TTL = 5L
        const val SHORT = 8
    }
}
