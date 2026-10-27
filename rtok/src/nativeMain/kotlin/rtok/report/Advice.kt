// Port of src/report/advice.rs (T22.5, D24): recommendations are rules over the ledgers, never a model call. Each
// finding prints what triggered it and the rows it read, ordered by the tokens it would recover.
package rtok.report

import rtok.config.Config
import rtok.measure.stats.fixed
import rtok.model.ReportBust
import rtok.model.ReportCache
import rtok.model.ReportLedgers
import rtok.model.ReportSections
import kotlin.math.ceil

/** Finding sink: recoverable tokens plus the rule triple. */
internal typealias Push = (tokens: Long, rule: String, finding: String, evidence: String) -> Unit

object Advice {
    /** "Fires often": in-window hook rows at or above this with no `Measurement` kind on the event's path. */
    const val OFTEN_HOOK_CALLS = 10uL

    /** Turns a grouped cache-bust finding names before it says "and K more". */
    private const val BUST_TURNS_NAMED = 5
    private const val PERCENT = 100.0
    private const val MIN_CHARS_PER_TOKEN = 0.1f

    /** The T22.5 rules over one ledger read, most recoverable tokens first (ties keep the rule order). */
    fun recommendations(
        ledgers: ReportLedgers,
        cfg: Config,
    ): List<Recommendation> {
        val out = mutableListOf<Pair<Long, Recommendation>>()
        val push: Push = { tokens, rule, finding, evidence -> out += tokens to Recommendation(rule, finding, evidence) }
        expandRate(ledgers, cfg, push)
        retirePlugin(ledgers, push)
        cacheBusts(ledgers.cache, push)
        idleHooks(ledgers, push)
        injectBudget(ledgers, cfg, push)
        archiveWindow(ledgers, cfg, push)
        topSinks(ledgers, cfg, push)
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /** The kinds an event can produce: `PreToolUse` denies through `guard`; injections record `inject`. */
    private fun kindsForHook(event: String): List<String> =
        when (event) {
            "PreToolUse" -> listOf("guard")
            "SessionStart", "UserPromptSubmit", "PostCompact" -> listOf("inject")
            else -> emptyList()
        }

    /** Hooks idle by design; `PostToolUse` and `SessionEnd` are deliberately not among them (T22.6). */
    internal fun idleByDesign(event: String) = event == "PreCompact" || event == "Stop"

    /** "1 row" / "N rows". */
    private fun count(
        n: ULong,
        what: String,
    ) = "$n $what${if (n == 1uL) "" else "s"}"

    /** (1) Expand rate above `[expand] max_rate`: the compression is lossier than it looks. */
    private fun expandRate(
        l: ReportLedgers,
        cfg: Config,
        push: Push,
    ) {
        val e = l.expand
        val max = cfg.expand.maxRate
        if (e.decisions <= 0 || !(e.rate > max)) return
        push(
            e.cost,
            "expand-rate",
            "expand rate ${fixed(PERCENT * e.rate, 1)}% (${e.expanded} of ${e.decisions} live-zone pointers re-read) is above [expand] max_rate " +
                "${fixed(PERCENT * max, 0)}% — re-expansions cost ${e.cost} est tokens over ${count(e.costRows, "row")}",
            "${count(e.decisions.toULong(), "archive_decisions row")}, ${count(e.costRows, "archive Measurement row")} of kind expand",
        )
    }

    /** (2) Net saving ≤ 0 costs more than it returns; all-zero rows are bookkeeping, not evidence. */
    private fun retirePlugin(
        l: ReportLedgers,
        push: Push,
    ) {
        for (r in l.savings.rows.filter { it.saved <= 0 && (it.estBefore != 0L || it.estAfter != 0L) }) {
            push(
                if (r.saved == Long.MIN_VALUE) Long.MAX_VALUE else -r.saved,
                "retire-plugin",
                "plugin ${r.plugin} net ${r.saved} est tokens over ${count(r.rows, "Measurement row")} (≤ 0) — retire it rather than stack it",
                count(r.rows, "${r.plugin} Measurement row"),
            )
        }
    }

    /** (3) Host-caused cache busts, one finding per (session, cause). */
    internal fun cacheBusts(
        c: ReportCache,
        push: Push,
    ) {
        val groups = LinkedHashMap<Pair<String, String>, MutableList<ReportBust>>()
        for (b in c.detail.filter { it.cause == "tools" || it.cause == "system" }) groups.getOrPut(b.session to b.cause) { mutableListOf() } += b
        for ((key, busts) in groups) {
            val (session, cause) = key
            val what = if (cause == "system") "system prompt" else "tool list"
            val create = busts.sumOf { it.cacheCreate }
            val finding =
                if (busts.size == 1) {
                    "session $session turn ${busts[0].turn} busted the prompt cache (cause $cause): $create cache-create tokens re-written with " +
                        "cache_read ${busts[0].cacheRead} — pin the $what; $create tokens would have stayed cached"
                } else {
                    var turns = busts.take(BUST_TURNS_NAMED).joinToString(", ") { it.turn.toString() }
                    if (busts.size > BUST_TURNS_NAMED) turns += " and ${busts.size - BUST_TURNS_NAMED} more"
                    "session $session busted the prompt cache ${count(busts.size.toULong(), "time")} (cause $cause, turns $turns): $create " +
                        "cache-create tokens re-written — pin the $what; $create tokens would have stayed cached"
                }
            push(create, "cache-bust", finding, "${count(c.busts, "cache bust")} over ${count(c.turns, "turn")} in ${count(c.sessions, "session")} with usage rows")
        }
    }

    /** (4) Idle-by-design hooks that fire often: latency on the 10 ms path for nothing. */
    private fun idleHooks(
        l: ReportLedgers,
        push: Push,
    ) {
        for (h in l.calls.hooks.filter { idleByDesign(it.name) }) {
            val worked = kindsForHook(h.name).any { it in l.savings.kinds }
            if (h.calls < OFTEN_HOOK_CALLS || worked) continue
            push(
                0,
                "idle-hook",
                "${h.name} fired ${h.calls} times in window with no Measurement kind recorded on its path — weight on the 10 ms hook path for nothing; 0 tokens saved",
                "${count(h.calls, "hook `${h.name}` row")} in window, ${count(l.window.measurements, "Measurement row")} in ledger",
            )
        }
    }

    /** (5) Measured injection per turn against the budget. */
    private fun injectBudget(
        l: ReportLedgers,
        cfg: Config,
        push: Push,
    ) {
        val budget =
            cfg.plugins.inject.budgetTokens
                .toLong()
        val r = l.savings.rows.firstOrNull { it.plugin == "inject" } ?: return
        val rows = r.rows.toLong()
        val allowed = if (rows != 0L && budget > Long.MAX_VALUE / rows) Long.MAX_VALUE else budget * rows
        if (rows <= 0 || r.estAfter <= allowed) return
        val excess = r.estAfter - allowed
        push(
            excess,
            "inject-budget",
            "inject averages ${r.estAfter / rows} est tokens/turn over ${count(r.rows, "inject Measurement row")} vs [plugins.inject] budget_tokens $budget — " +
                "$excess tokens over budget; trim modes and memory recall",
            count(r.rows, "inject Measurement row"),
        )
    }

    /** (6) `keep_turns` against observed re-reads: expanded pointers were archived young. */
    private fun archiveWindow(
        l: ReportLedgers,
        cfg: Config,
        push: Push,
    ) {
        val e = l.expand
        if (e.expanded <= 0) return
        push(
            e.cost,
            "archive-window",
            "archive keep_turns ${cfg.plugins.archive.keepTurns} with ${e.expanded} of ${e.decisions} pointers re-read via rtok expand (${e.cost} est tokens over " +
                "${count(e.costRows, "row")}) — old results ARE re-read; raise keep_turns or min_tokens so volatile results stay live",
            "${count(e.decisions.toULong(), "archive_decisions row")}, ${count(e.costRows, "expand Measurement row")}",
        )
    }

    /** (7) Top token sinks, ranked by estimated tokens (bytes / `[estimator] code`), the unit every rule sorts on. */
    internal fun topSinks(
        l: ReportLedgers,
        cfg: Config,
        push: Push,
    ) {
        for (s in l.sinks.rows.filter { it.beforeBytes >= 1 && ReportSections.isActionableSink(it.cls) }) {
            val est = maxOf(ceil(s.beforeBytes.toDouble() / maxOf(cfg.estimator.code, MIN_CHARS_PER_TOKEN).toDouble()), 1.0).toLong()
            push(
                est,
                "top-sinks",
                "${s.cls} ${s.sink} cost ${s.beforeBytes} bytes over ${count(s.rows, "Measurement row")} — ${s.switch}",
                "${count(s.rows, "${s.sink} row")} ${s.cls} Measurement rows, ${count(l.window.measurements, "Measurement row")} total in ledger",
            )
        }
    }
}
