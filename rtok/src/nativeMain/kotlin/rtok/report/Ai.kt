// Port of src/report/ai.rs (T22.4): the `--ai` rendering of [Document]: dense plain lines, stable `#id` anchors,
// tables in the `toon` plugin's encoding (`k=v` lines when a table is not tabular). One budget
// (`[report] budget_tokens`): whole sections are dropped past it and named in `dropped:`, never cut mid-table.
package rtok.report

import rtok.agents.ModuleState
import rtok.config.Config
import rtok.model.ReportLedgers
import rtok.plugin.sdk.Class
import rtok.report.ReportText.dec
import rtok.report.ReportText.flat
import rtok.report.ReportText.ms

object Ai {
    /** Stable section anchors, in the fixed P22 order. */
    val IDS = listOf("window", "savings", "calls", "cache", "expand", "config", "doctor", "recommendations")

    private const val PERCENT = 100.0
    private const val NO_ROWS = "no rows.\n"

    /**
     * Render under `cfg.report.budget_tokens`, counting the complete text with the configured estimator. Sections that do
     * not fit are dropped whole, in order; when not even the header and a full `dropped:` list fit, the output shrinks to
     * the title and that list, and to nothing when that is still over the cap.
     */
    fun render(
        doc: Document,
        cfg: Config,
    ): String {
        val budget = cfg.report.budgetTokens

        fun fits(s: String) = rtok.estimate(s, Class.Prose, cfg.estimator).toUInt() <= budget
        val kept = mutableListOf<Pair<String, String>>()
        for (id in IDS) {
            kept += id to section(id, doc)
            if (!fits(assemble(doc, kept, full = true))) kept.removeAt(kept.lastIndex)
        }
        // The `dropped:` line grew with every later rejection; give back sections until the final text fits.
        while (true) {
            val full = assemble(doc, kept, full = true)
            if (fits(full)) return full
            if (kept.isEmpty()) break
            kept.removeAt(kept.lastIndex)
        }
        return assemble(doc, kept, full = false).takeIf(::fits) ?: ""
    }

    private fun assemble(
        doc: Document,
        kept: List<Pair<String, String>>,
        full: Boolean,
    ): String =
        buildString {
            val dropped = IDS.filter { id -> kept.none { it.first == id } }
            append("rtok-report ai\n")
            if (full) {
                val w = doc.ledgers.window
                append("range ${w.fromDate}..${w.toDate} window=${w.since} store=${w.dbPath}\n")
            }
            append("dropped: ${if (dropped.isEmpty()) "none" else dropped.joinToString(",")}\n")
            for ((id, body) in kept) append("#$id\n$body")
        }

    private fun section(
        id: String,
        doc: Document,
    ): String =
        when (id) {
            "window" -> window(doc)
            "savings" -> savings(doc)
            "calls" -> calls(doc)
            "cache" -> cache(doc)
            "expand" -> expand(doc)
            "config" -> config(doc)
            "doctor" -> doctor(doc)
            else -> recommendations(doc)
        }

    private fun window(doc: Document): String {
        val w = doc.ledgers.window
        val rows =
            listOf(
                listOf("calls", "${w.callsInWindow}", "${w.callsTotal}", "window ${w.since}"),
                listOf("measurements", "", "${w.measurements}", "whole ledger no row times"),
                listOf("usage", "", "${w.usage}", "whole ledger no row times"),
            )
        return "units: row counts per ledger\n" + aiTable(listOf("ledger", "in_window", "total", "coverage"), rows) + "since=${w.since}\n"
    }

    private fun savings(doc: Document): String {
        val sav = doc.ledgers.savings
        val head = "units: est tokens, floors from Measurement rows (no turn counts); expand rows negative\n"
        if (sav.rows.isEmpty()) return head + NO_ROWS
        val rows = sav.rows.map { listOf(it.plugin, "${it.rows}", "${it.estBefore}", "${it.estAfter}", "${it.saved}") }
        return head + aiTable(listOf("plugin", "rows", "est_before", "est_after", "saved"), rows) + "total_saved=${sav.totalSaved}tok rows=${sav.totalRows}\n"
    }

    private fun calls(doc: Document): String {
        val calls = doc.ledgers.calls
        val head = "units: counts; p50_ms/p95_ms nearest-rank over timed calls, blank when untimed\n"
        if (calls.total == 0uL) return head + NO_ROWS
        val rows = calls.rows.map { listOf(it.surface, "${it.calls}", "${it.timed}", ms(it.p50Ms), ms(it.p95Ms)) }
        return head + aiTable(listOf("surface", "calls", "timed", "p50_ms", "p95_ms"), rows) +
            "in_window=${calls.inWindow} total=${calls.total} window=${doc.ledgers.window.since}\n"
    }

    private fun cache(doc: Document): String =
        buildString {
            val cache = doc.ledgers.cache
            append("units: counts from usage rows, whole ledger\n")
            if (cache.sessions == 0uL) {
                append(NO_ROWS)
            } else {
                // Two columns are not tabular for the toon encoder (it needs ≥ 3): `table` falls back on its own.
                append(aiTable(listOf("cause", "busts"), cache.byCause.map { listOf(it.first, "${it.second}") }))
                append("turns=${cache.turns} sessions=${cache.sessions} busts=${cache.busts}\n")
            }
            if (doc.ledgers.lanes.isNotEmpty()) {
                append("hit = cache_read over all input, per proxy lane/tier\n")
                append(aiTable(ReportLedgers.LANE_HEADS, doc.ledgers.laneCells()))
            }
        }

    private fun expand(doc: Document): String {
        val exp = doc.ledgers.expand
        val head = "units: counts; rate = expanded/decisions\n"
        if (exp.decisions == 0L) return head + NO_ROWS
        val ids = if (exp.expandedIds.isEmpty()) "none" else exp.expandedIds.joinToString(",") { ReportText.shownArchiveId(it) }
        return head + "decisions=${exp.decisions} expanded=${exp.expanded} rate=${dec(PERCENT * exp.rate)}% expanded_ids=$ids\n"
    }

    private fun config(doc: Document): String = "units: effective keys with origin\n" + aiTable(listOf("key", "value", "source"), doc.config.map { listOf(it.key, it.value, it.source) }) + "keys=${doc.config.size}\n"

    /** The doctor page with the agents block cut to the host variants where rtok has a module installed; the rest are counted. */
    internal fun doctor(doc: Document): String {
        val report = doc.doctor.copy(agents = doc.doctor.agents.filter { a -> a.modules.any { it.state == ModuleState.Installed } })
        val hidden = doc.doctor.agents.size - report.agents.size
        return buildString {
            append("units: live probes, not store rows\n")
            append(report.toCompactText())
            if (!endsWith('\n')) append('\n')
            if (hidden > 0) append("agents without rtok: $hidden (rtok doctor lists them)\n")
        }
    }

    private fun recommendations(doc: Document): String {
        val head = "units: rules over ledgers, most recoverable tokens first\n"
        if (doc.recommendations.isEmpty()) return head + "no recommendations.\n"
        return head + doc.recommendations.withIndex().joinToString("") { (i, r) -> "${i + 1}. [${r.rule}] ${r.finding} (${r.evidence})\n" }
    }
}
