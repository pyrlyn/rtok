// Port of the agents half of src/render.rs (T25.2, T25.3, T284): `duration`, `ago`, `agent_cells`, the
// `rtok agents sessions` table and its watch tick.
package rtok.agents

import rtok.Col
import rtok.log.WatchTick
import rtok.log.stamp
import rtok.table

object SessionsTable {
    private const val MIN = 60L
    private const val HOUR = 3_600L
    private const val DAY = 86_400L
    private const val SUB_BLANKS = 6

    private fun p2(n: Long) = n.toString().padStart(2, '0')

    /** Two units at most: `45s`, `1m05s`, `1h01m`, `1d01h`; clock skew is not a negative runtime. */
    fun duration(secs: Long): String {
        val s = secs.coerceAtLeast(0)
        return when {
            s < MIN -> "${s}s"
            s < HOUR -> "${s / MIN}m${p2(s % MIN)}s"
            s < DAY -> "${s / HOUR}h${p2(s % HOUR / MIN)}m"
            else -> "${s / DAY}d${p2(s % DAY / HOUR)}h"
        }
    }

    fun ago(
        now: Long,
        ts: Long,
    ) = "${duration(now - ts)} ago"

    /** id, seen, state, worktree, activity; the agent's own status text wins over the hook's last activity. */
    private fun agentCells(
        a: AgentView,
        indent: String,
        now: Long,
    ): List<String> {
        val d = a.detail
        return listOf("$indent${d.short}", ago(now, d.lastSeen), a.state.label, a.worktree ?: "-", d.statusText ?: d.activity ?: "-")
    }

    private val HEADER =
        listOf("agent", "host", "provider", "model", "input", "output", "cache_read", "cache_create", "started", "run", "seen", "state", "worktree", "activity")

    /** Left for text, right for numbers and durations. */
    private val COLS = "LLLLRRRRLRRLLL".map { if (it == 'R') Col.right(0) else Col.left(0) }

    private fun run(
        start: Long,
        end: Long?,
        now: Long,
    ) = duration((end ?: now) - start)

    private fun sessionRow(
        v: SessionView,
        now: Long,
    ): List<String> {
        val r = v.session
        val c = v.agent?.let { agentCells(it, "", now) } ?: listOf("-", ago(now, r.lastActivity), "", "-", "-")
        val usage = listOf(r.input, r.output, r.cacheRead, r.cacheCreate).map(Long::toString)
        return listOf(c[0], r.host ?: "-", r.provider ?: r.api ?: "-", r.model ?: "-") + usage +
            listOf(stamp(r.startedAt.coerceAtLeast(0)), run(r.startedAt, r.endedAt, now), c[1], v.state.label) + c.drop(CELL_WORKTREE)
    }

    private fun subRow(
        sub: AgentView,
        now: Long,
    ): List<String> {
        val c = agentCells(sub, "  ", now)
        val d = sub.detail
        return listOf(c[0], d.host) + List(SUB_BLANKS) { "-" } + listOf(stamp(d.startedAt.coerceAtLeast(0)), run(d.startedAt, d.endedAt, now)) + c.drop(1)
    }

    private const val CELL_WORKTREE = 3

    /** Newest first, each row followed by its sub-agents indented; ended rows only with [all]. */
    fun render(
        rows: List<SessionView>,
        all: Boolean,
        now: Long,
    ): String {
        val shown = rows.filter { all || it.state != AgentState.Ended }
        val body =
            listOf(HEADER) +
                shown.flatMap { v ->
                    listOf(sessionRow(v, now)) +
                        v.agent
                            ?.subAgents
                            .orEmpty()
                            .filter { all || it.state != AgentState.Ended }
                            .map { subRow(it, now) }
                }
        val out = table(COLS, body)
        return when {
            shown.isNotEmpty() -> out
            all -> out + "no sessions\n"
            else -> out + "nothing is running\n"
        }
    }

    /** One `sessions watch` step: the whole table as fresh when it changed since [prev], else nothing fresh. */
    class Ticker {
        private var prev = ""

        fun tick(
            rows: List<SessionView>,
            all: Boolean,
            now: Long,
        ): WatchTick {
            val text = render(rows, all, now)
            val screen = text.lines().dropLast(1)
            if (text == prev) return WatchTick(emptyList(), screen)
            prev = text
            return WatchTick(screen, screen)
        }

        /** The last screen, kept on a transient unreadable store (a missed poll, not a blank screen). */
        fun keep(): WatchTick = WatchTick(emptyList(), prev.lines().dropLast(1))
    }
}
