// Port of src/render.rs `agent_whoami_text` and `agent_show_text` (T283, T284): one agent as `key: value` lines.
// The host's own session id stays in `--json` only: it is the host's key, not rtok's identity (D34).
package rtok.agents

import rtok.log.stamp
import rtok.store.AgentDetail

object AgentText {
    private fun dash(s: String?) = s ?: "-"

    fun whoami(d: AgentDetail): String =
        "id: ${d.id}\nshort: ${d.short}\nhost: ${d.host}\ncwd: ${dash(d.cwd)}\nstarted: ${stamp(d.startedAt.coerceAtLeast(0))}\n" +
            "last seen: ${stamp(d.lastSeen.coerceAtLeast(0))}\nactivity: ${dash(d.activity)}\n"

    fun show(
        a: AgentView,
        now: Long,
    ): String {
        val d = a.detail
        val subs = a.subAgents.joinToString(", ") { "${it.detail.short} (${it.state.label})" }.ifEmpty { "-" }
        return "id: ${d.id}\nhost: ${d.host}\nmodel: ${dash(a.model)}\nparent: ${dash(d.parentId)}\nsub-agents: $subs\n" +
            "cwd: ${dash(d.cwd)}\nworktree: ${dash(a.worktree)}\nclaimed: ${a.worktrees.joinToString(", ").ifEmpty { "-" }}\n" +
            "unread: ${a.unread}\nstate: ${a.state.label}\nactivity: ${dash(d.activity)}\nstatus: ${dash(d.statusText)}\n" +
            "started: ${stamp(d.startedAt.coerceAtLeast(0))}\nlast seen: ${stamp(d.lastSeen.coerceAtLeast(0))} (${SessionsTable.ago(now, d.lastSeen)})\n"
    }
}
