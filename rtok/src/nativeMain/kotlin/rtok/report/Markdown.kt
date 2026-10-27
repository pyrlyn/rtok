// Port of src/report/markdown.rs (T22.1): the Markdown rendering of [Document]: tables, no charts. It formats; it
// computes nothing (D24), and a section with no rows says so instead of printing zeros.
package rtok.report

import rtok.model.ReportLedgers
import rtok.report.ReportText.dec
import rtok.report.ReportText.ms

object Markdown {
    private const val PERCENT = 100.0

    /** The fixed P22 section order: Window · Savings · Calls · Cache · Expand · Config · Doctor · Recommendations. */
    fun render(doc: Document): String =
        buildString {
            val w = doc.ledgers.window
            append("# rtok report\n\nWindow ${w.fromDate} → ${w.toDate} (${w.since}) · store ${w.dbPath}\n\n")
            window(doc)
            savings(doc)
            calls(doc)
            cache(doc)
            expand(doc)
            config(doc)
            append("\n## Doctor\n\nLive probes (the `rtok doctor` page), not store rows.\n\n```text\n${doc.doctor.toText()}```\n\n")
            append("## Recommendations\n\n")
            if (doc.recommendations.isEmpty()) append("No recommendations.\n")
            for (r in doc.recommendations) append("- **${cell(r.rule)}**: ${cell(r.finding)} (${cell(r.evidence)})\n")
        }

    private fun StringBuilder.window(doc: Document) {
        val w = doc.ledgers.window
        append("## Window\n\n| ledger | rows | covers |\n| --- | --- | --- |\n")
        append("| calls | ${w.callsInWindow} of ${w.callsTotal} | window (${w.since}) |\n")
        append("| measurements | ${w.measurements} | whole ledger (no row times) |\n")
        append("| usage | ${w.usage} | whole ledger (no row times) |\n")
        if (w.callsTotal == 0uL && w.measurements == 0uL && w.usage == 0uL) append("\n**No rows in window.** The store has no rows to report.\n")
    }

    private fun StringBuilder.savings(doc: Document) {
        val sav = doc.ledgers.savings
        append(
            "\n## Savings\n\nEstimated tokens from `Measurement` rows only: est_before − est_after, net — an `expand` row counts " +
                "negative, because retrieval costs tokens. A `Measurement` row carries no turn count, so these are floors, not " +
                "context-token-turns.\n\n",
        )
        if (sav.rows.isEmpty()) {
            append("No rows in window.\n")
            return
        }
        append("| plugin | rows | est before | est after | saved |\n| --- | ---: | ---: | ---: | ---: |\n")
        for (r in sav.rows) append("| ${cell(r.plugin)} | ${r.rows} | ${r.estBefore} | ${r.estAfter} | ${r.saved} |\n")
        append("\nTotal: ${sav.totalSaved} est tokens over ${sav.totalRows} `Measurement` rows (whole ledger: no row times).\n")
    }

    private fun StringBuilder.calls(doc: Document) {
        val calls = doc.ledgers.calls
        append("\n## Calls\n\nLatency per surface, nearest-rank p50/p95 over the calls that recorded one.\n\n")
        if (calls.total == 0uL) {
            append("No rows in window.\n")
            return
        }
        append("| surface | calls | timed | p50 ms | p95 ms |\n| --- | ---: | ---: | ---: | ---: |\n")
        for (r in calls.rows) append("| ${cell(r.surface)} | ${r.calls} | ${r.timed} | ${ms(r.p50Ms)} | ${ms(r.p95Ms)} |\n")
        append(
            "\n${calls.inWindow} of ${calls.total} `calls` rows in window (${doc.ledgers.window.since}); surfaces beyond " +
                "hook/mcp/proxy are not in the section set.\n",
        )
    }

    private fun StringBuilder.cache(doc: Document) {
        val cache = doc.ledgers.cache
        append("\n## Cache\n\nPrompt-cache busts by cause, from the proxy's `usage` rows (whole ledger).\n\n")
        if (cache.sessions == 0uL) {
            append("No rows in window.\n")
        } else {
            append("| cause | busts |\n| --- | ---: |\n")
            for ((cause, n) in cache.byCause) append("| ${cell(cause)} | $n |\n")
            append("\nBusts: ${cache.busts} over ${cache.turns} turns in ${cache.sessions} session(s).\n")
        }
        if (doc.ledgers.lanes.isNotEmpty()) {
            append("\nPrompt-cache hit rate per proxy lane and service tier:\n\n| ${ReportLedgers.LANE_HEADS.joinToString(" | ")} |\n")
            append("| --- | ---: | ---: | ---: | ---: | ---: |\n")
            for (row in doc.ledgers.laneCells()) append("| ${row.joinToString(" | ") { cell(it) }} |\n")
        }
    }

    private fun StringBuilder.expand(doc: Document) {
        val exp = doc.ledgers.expand
        append("\n## Expand\n\nHow often a live-zone pointer had to be expanded (`archive_decisions` rows).\n\n")
        if (exp.decisions == 0L) {
            append("No rows in window.\n")
            return
        }
        val what = if (exp.expandedIds.isEmpty()) "none" else exp.expandedIds.joinToString(", ") { cell(ReportText.shownArchiveId(it)) }
        append("`rtok expand` froze ${exp.expanded} of ${exp.decisions} live-zone pointers (${dec(PERCENT * exp.rate)}%). Expanded: $what.\n")
    }

    private fun StringBuilder.config(doc: Document) {
        append("\n## Config\n\nEvery effective key with its origin — the `config show --sources` page.\n\n")
        append("| key | value | source |\n| --- | --- | --- |\n")
        for (e in doc.config) append("| ${cell(e.key)} | ${cell(e.value)} | ${cell(e.source)} |\n")
        append("\n${doc.config.size} keys.\n")
    }

    /** A table cell: `|` is escaped, a newline folds to a space. */
    private fun cell(s: String) = ReportText.flat(s).replace("|", "\\|")
}
